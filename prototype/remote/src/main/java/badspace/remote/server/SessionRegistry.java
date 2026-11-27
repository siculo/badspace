package badspace.remote.server;

import badspace.remote.api.RequestId;
import badspace.remote.api.errors.CommunicationException;
import badspace.remote.api.errors.NodeRecoveryRequiredException;
import badspace.remote.api.errors.SessionUnavailableException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Tracks active sessions, requests in progress and saved outcomes. */
final class SessionRegistry {
    // The registry monitor guards sessions, admission, expiration and publication of outcomes.
    // Operations and waits for requests or revocations run outside the monitor.
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final long requestTimeout;
    private final long sessionTimeout;
    private final Clock clock;
    private boolean closed;

    SessionRegistry(long requestTimeout, long sessionTimeout, Clock clock) {
        if (requestTimeout < 0 || sessionTimeout <= 0) {
            throw new IllegalArgumentException("Invalid retention timeouts: " + requestTimeout + ", " + sessionTimeout);
        }
        this.requestTimeout = requestTimeout;
        this.sessionTimeout = sessionTimeout;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized UUID open() {
        if (closed) {
            throw new NodeRecoveryRequiredException("Server endpoint is closed");
        }
        var token = UUID.randomUUID();
        sessions.put(token, new Session(clock.millis()));
        return token;
    }

    void revoke(UUID token) {
        startRevocation(Objects.requireNonNull(token, "token")).join();
    }

    void close() {
        List<CompletableFuture<Void>> revoked = new ArrayList<>();
        synchronized (this) {
            closed = true;
            for (UUID token : List.copyOf(sessions.keySet())) {
                revoked.add(startRevocation(token));
            }
        }
        revoked.forEach(CompletableFuture::join);
    }

    private synchronized CompletableFuture<Void> startRevocation(UUID token) {
        var session = sessions.get(token);
        if (session == null) {
            return CompletableFuture.completedFuture(null);
        }
        session.revoked = true;
        finishRevocation(token, session);
        return session.drained;
    }

    private void finishRevocation(UUID token, Session session) {
        if (session.revoked && session.running == 0) {
            sessions.remove(token);
            session.drained.complete(null);
        }
    }

    @SuppressWarnings("unchecked")
    <T> T executeOnceAndReturn(RequestId requestId, Supplier<T> operation) {
        var admission = admit(requestId);
        var request = admission.request();
        if (!admission.execute()) {
            return (T) request.outcome.join().value();
        }

        T result;
        try {
            result = operation.get();
        } catch (Throwable failure) {
            finish(requestId, admission, new Outcome(null, failure));
            throw failure;
        }
        finish(requestId, admission, new Outcome(result, null));
        return result;
    }

    private synchronized Admission admit(RequestId requestId) {
        var token = requestId.sessionToken();
        var session = sessions.get(token);
        if (session == null || session.revoked || removeExpired(token, session, clock.millis())) {
            throw new SessionUnavailableException("Session %s is unavailable".formatted(token));
        }
        long sequence = requestId.sequence();
        var request = session.requests.get(sequence);
        if (request != null) {
            return new Admission(session, request, false);
        }
        if (sequence <= session.lastExpiredSequence) {
            throw new NodeRecoveryRequiredException("Request %s expired".formatted(requestId));
        }
        request = new Request();
        session.requests.put(sequence, request);
        session.running++;
        return new Admission(session, request, true);
    }

    private synchronized void finish(RequestId requestId, Admission admission, Outcome outcome) {
        var session = admission.session();
        var request = admission.request();
        long now = clock.millis();
        Throwable failure = outcome.failure();
        if (failure == null || (failure instanceof RuntimeException && !(failure instanceof CommunicationException))) {
            request.completedAt = now;
        } else {
            session.requests.remove(requestId.sequence());
            if (session.requests.isEmpty()) {
                session.emptySince = now;
            }
        }
        session.running--;
        request.outcome.complete(outcome);
        finishRevocation(requestId.sessionToken(), session);
    }

    synchronized void removeExpired() {
        long now = clock.millis();
        var entries = sessions.values().iterator();
        while (entries.hasNext()) {
            var session = entries.next();
            if (session.revoked) {
                continue;
            }
            removeExpiredRequests(session, now);
            if (isExpired(session, now)) {
                entries.remove();
            }
        }
    }

    private boolean removeExpired(UUID token, Session session, long now) {
        removeExpiredRequests(session, now);
        if (!isExpired(session, now)) {
            return false;
        }
        sessions.remove(token);
        return true;
    }

    private void removeExpiredRequests(Session session, long now) {
        if (session.requests.isEmpty()) {
            return;
        }
        var entries = session.requests.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            var request = entry.getValue();
            if (request.outcome.isDone() && now - request.completedAt > requestTimeout) {
                session.lastExpiredSequence = Math.max(session.lastExpiredSequence, entry.getKey());
                entries.remove();
            }
        }
        if (session.requests.isEmpty()) {
            session.emptySince = now;
        }
    }

    private boolean isExpired(Session session, long now) {
        return session.requests.isEmpty() && now - session.emptySince >= sessionTimeout;
    }

    private static final class Session {
        private final Map<Long, Request> requests = new HashMap<>();
        private final CompletableFuture<Void> drained = new CompletableFuture<>();
        private long lastExpiredSequence = -1;
        private long emptySince;
        private int running;
        private boolean revoked;

        private Session(long openedAt) {
            emptySince = openedAt;
        }
    }

    private static final class Request {
        private final CompletableFuture<Outcome> outcome = new CompletableFuture<>();
        private long completedAt;
    }

    private record Admission(Session session, Request request, boolean execute) {}

    private record Outcome(Object result, Throwable failure) {
        Object value() {
            return switch (failure) {
                case null -> result;
                case RuntimeException error -> throw error;
                case Error error -> throw error;
                default -> throw new IllegalStateException("Unexpected request failure", failure);
            };
        }
    }
}
