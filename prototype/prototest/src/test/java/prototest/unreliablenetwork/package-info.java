/**
 * Tests of the proxy behaviour on an unreliable network.
 *
 * <p>The tests use the real {@link prototest.example.proxy.NodeProxy} and the real server
 * adapter {@link prototest.example.server.NodeTestEndpoint}, usually with a
 * {@link prototest.remote.network.FakeNetworkInterface} between them. The fake network loses or
 * holds single requests and responses, following explicit rules: there are no random
 * failures or delays, so each test always runs the same scenario.
 *
 * <ul>
 *   <li>{@link prototest.unreliablenetwork.RetryTest}: lost messages that the proxy
 *       hides with retries, and the limit after which it stops retrying.</li>
 *   <li>{@link prototest.unreliablenetwork.RecoveryTest}: failures that require the
 *       recovery of the proxy, what the recovery allows, and late responses that
 *       arrive after it.</li>
 *   <li>{@link prototest.unreliablenetwork.SessionLifecycleTest}: connection, recovery
 *       and disconnection when their messages are lost, and old requests that reach
 *       the server after their session was revoked or has expired.</li>
 * </ul>
 *
 * <p>The tests that need a final error from the endpoint, without a lost message,
 * use their own endpoints and are in {@code prototest.example.proxy.NodeProxyRecoveryTest}.
 * The tests of the fake network itself are in {@code prototest.fakenetwork}.
 */
package prototest.unreliablenetwork;
