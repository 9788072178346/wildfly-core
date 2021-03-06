/*
Copyright 2017 Red Hat, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package org.jboss.as.controller;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CANCELLED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.logging.ControllerLogger.ROOT_LOGGER;

import java.io.IOException;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.jboss.as.controller.access.InVmAccess;
import org.jboss.as.controller.client.Operation;
import org.jboss.as.controller.client.OperationAttachments;
import org.jboss.as.controller.client.OperationMessageHandler;
import org.jboss.as.controller.client.OperationResponse;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.dmr.ModelNode;
import org.jboss.threads.AsyncFuture;
import org.jboss.threads.AsyncFutureTask;
import org.wildfly.security.auth.server.SecurityIdentity;

/**
 * Standard implementation of {@link ModelControllerClientFactory}.
 *
 * @author Brian Stansberry
 */
final class ModelControllerClientFactoryImpl implements ModelControllerClientFactory {

    private final ModelController modelController;
    private final Supplier<SecurityIdentity> securityIdentitySupplier;

    ModelControllerClientFactoryImpl(ModelController modelController, Supplier<SecurityIdentity> securityIdentitySupplier) {
        this.modelController = modelController;
        this.securityIdentitySupplier = securityIdentitySupplier;
    }

    @Override
    public LocalModelControllerClient createClient(Executor executor) {
        return createLocalClient(executor);
    }

    @Override
    public LocalModelControllerClient createSuperUserClient(Executor executor) {
        // Wrap a standard LocalClient returned for non-SuperUser calls with
        // one that provides superuser privileges
        final LocalClient delegate = createLocalClient(executor);
        return new LocalModelControllerClient() {
            @Override
            public OperationResponse executeOperation(final Operation operation, final OperationMessageHandler messageHandler) {
                return executeInVm(delegate::executeOperation, operation, messageHandler);
            }

            @Override
            public AsyncFuture<ModelNode> executeAsync(final Operation operation, final OperationMessageHandler messageHandler) {
                return executeInVm(delegate::executeAsync, operation, messageHandler);
            }

            @Override
            public AsyncFuture<OperationResponse> executeOperationAsync(final Operation operation, final OperationMessageHandler messageHandler) {
                return executeInVm(delegate::executeOperationAsync, operation, messageHandler);
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }

    private LocalClient createLocalClient(Executor executor) {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(ModelController.ACCESS_PERMISSION);
        }

        return new LocalClient(modelController, securityIdentitySupplier, executor);
    }

    /**
     * A client that does not automatically grant callers SuperUser rights.
     */
    private static class LocalClient implements LocalModelControllerClient {

        private final ModelController modelController;
        private final Supplier<SecurityIdentity> securityIdentitySupplier;
        private final Executor executor;

        private LocalClient(ModelController modelController, Supplier<SecurityIdentity> securityIdentitySupplier, Executor executor) {
            this.modelController = modelController;
            this.securityIdentitySupplier = securityIdentitySupplier;
            this.executor = executor;
        }

        @Override
        public void close()  {
            // whatever
        }

        @Override
        public OperationResponse executeOperation(Operation operation, OperationMessageHandler messageHandler) {
            return modelController.execute(operation, messageHandler, ModelController.OperationTransactionControl.COMMIT);
        }

        @Override
        public AsyncFuture<ModelNode> executeAsync(final Operation operation, final OperationMessageHandler messageHandler) {
            return executeAsync(operation.getOperation(), messageHandler, operation, ResponseConverter.TO_MODEL_NODE);
        }

        @Override
        public AsyncFuture<OperationResponse> executeOperationAsync(Operation operation, OperationMessageHandler messageHandler) {
            return executeAsync(operation.getOperation(), messageHandler, operation, ResponseConverter.TO_OPERATION_RESPONSE);
        }

        private <T> AsyncFuture<T> executeAsync(final ModelNode operation, final OperationMessageHandler messageHandler,
                                                final OperationAttachments attachments,
                                                final ResponseConverter<T> responseConverter) {
            if (executor == null) {
                throw ControllerLogger.ROOT_LOGGER.nullAsynchronousExecutor();
            }

            final AtomicReference<Thread> opThread = new AtomicReference<Thread>();
            final ResponseFuture<T> responseFuture = new ResponseFuture<T>(opThread, responseConverter, executor);

            final SecurityIdentity securityIdentity = securityIdentitySupplier.get();
            final boolean inVmCall = SecurityActions.isInVmCall();

            executor.execute(new Runnable() {
                public void run() {
                    try {
                        if (opThread.compareAndSet(null, Thread.currentThread())) {
                            // We need the AccessAuditContext as that will make any inflowed SecurityIdentity available.
                            OperationResponse response = AccessAuditContext.doAs(securityIdentity, null, new PrivilegedAction<OperationResponse>() {

                                @Override
                                public OperationResponse run() {
                                    Operation op = attachments == null ? Operation.Factory.create(operation) : Operation.Factory.create(operation, attachments.getInputStreams(),
                                            attachments.isAutoCloseStreams());
                                    if (inVmCall) {
                                        return SecurityActions.runInVm(() -> modelController.execute(op, messageHandler, ModelController.OperationTransactionControl.COMMIT));
                                    } else {
                                        return modelController.execute(op, messageHandler, ModelController.OperationTransactionControl.COMMIT);
                                    }
                                }
                            });
                            responseFuture.handleResult(response);
                        }
                    } finally {
                        synchronized (opThread) {
                            opThread.set(null);
                            opThread.notifyAll();
                        }
                    }
                }
            });
            return responseFuture;
        }
    }

    /**
     * {@link AsyncFuture} implementation returned by the {@code executeAsync} and {@code executeOperationAsync}
     * methods of clients produced by this factory.
     *
     * @param <T> the type of response object returned by the future ({@link ModelNode} or {@link OperationResponse})
     */
    private static class ResponseFuture<T> extends AsyncFutureTask<T> {

        private final AtomicReference<Thread> opThread;
        private final ResponseConverter<T> responseConverter;

        private ResponseFuture(final AtomicReference<Thread> opThread,
                               final ResponseConverter<T> responseConverter, final Executor executor) {
            super(executor);
            this.opThread = opThread;
            this.responseConverter = responseConverter;
        }

        public void asyncCancel(final boolean interruptionDesired) {
            Thread thread = opThread.getAndSet(Thread.currentThread());
            if (thread == null) {
                setCancelled();
            } else {
                // Interrupt the request execution
                thread.interrupt();
                // Wait for the cancellation to clear opThread
                boolean interrupted = false;
                synchronized (opThread) {
                    while (opThread.get() != null) {
                        try {
                            opThread.wait();
                        } catch (InterruptedException ie) {
                            interrupted = true;
                        }
                    }
                }
                setCancelled();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        void handleResult(final OperationResponse result) {
            ModelNode responseNode = result == null ? null : result.getResponseNode();
            if (responseNode != null && responseNode.hasDefined(OUTCOME) && CANCELLED.equals(responseNode.get(OUTCOME).asString())) {
                setCancelled();
            } else {
                setResult(responseConverter.fromOperationResponse(result));
            }
        }
    }

    private interface ResponseConverter<T> {

        T fromOperationResponse(OperationResponse or);

        ResponseConverter<ModelNode> TO_MODEL_NODE = new ResponseConverter<ModelNode>() {
            @Override
            public ModelNode fromOperationResponse(OperationResponse or) {
                ModelNode result = or.getResponseNode();
                try {
                    or.close();
                } catch (IOException e) {
                    ROOT_LOGGER.debugf(e, "Caught exception closing %s whose associated streams, "
                            + "if any, were not wanted", or);
                }
                return result;
            }
        };

        ResponseConverter<OperationResponse> TO_OPERATION_RESPONSE = new ResponseConverter<OperationResponse>() {
            @Override
            public OperationResponse fromOperationResponse(final OperationResponse or) {
                return or;
            }
        };
    }

    private static <T, U, R> R executeInVm(BiFunction<T, U, R> function, T t, U u) {
        try {
            return InVmAccess.runInVm((PrivilegedExceptionAction<R>) () -> function.apply(t, u) );
        } catch (PrivilegedActionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            } else if (cause instanceof Error) {
                throw (Error) cause;
            } else {
                // Not really possible as BiFunction doesn't throw checked exceptions
                throw new RuntimeException(cause);
            }
        }
    }
}
