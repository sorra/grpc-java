/*
 * Copyright 2023 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.util;

import io.grpc.Context;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * An optional ServerInterceptor that can interrupt server calls that are running for too long time.
 * In this way, it prevents problematic code from using up all threads.
 *
 * <p>How to use: you can add it to your server using ServerBuilder#intercept(ServerInterceptor).
 *
 * <p>Limitation: it only applies the timeout to unary calls
 * (streaming calls will still run without timeout).
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10361")
public class ServerCallTimeoutInterceptor implements ServerInterceptor {

  private final ServerTimeoutManager serverTimeoutManager;

  public ServerCallTimeoutInterceptor(ServerTimeoutManager serverTimeoutManager) {
    this.serverTimeoutManager = serverTimeoutManager;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> serverCall,
      Metadata metadata,
      ServerCallHandler<ReqT, RespT> serverCallHandler) {
    // Only intercepts unary calls because the timeout is inapplicable to streaming calls.
    if (serverCall.getMethodDescriptor().getType().clientSendsOneMessage()) {
      ServerCall<ReqT, RespT> serializingServerCall = new SerializingServerCall<>(serverCall);
      Context.CancellableContext timeoutContext =
              serverTimeoutManager.startTimeoutContext(serializingServerCall);
      if (timeoutContext != null) {
        return new TimeoutServerCallListener<>(
                serverCallHandler.startCall(serializingServerCall, metadata),
                timeoutContext,
                serverTimeoutManager);
      }
    }
    return serverCallHandler.startCall(serverCall, metadata);
  }

  /** A listener that intercepts RPC callbacks for timeout control. */
  static class TimeoutServerCallListener<ReqT>
      extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {

    private final Context.CancellableContext context;
    private final ServerTimeoutManager serverTimeoutManager;

    private TimeoutServerCallListener(
        ServerCall.Listener<ReqT> delegate,
        Context.CancellableContext context,
        ServerTimeoutManager serverTimeoutManager) {
      super(delegate);
      this.context = context;
      this.serverTimeoutManager = serverTimeoutManager;
    }

    @Override
    public void onMessage(ReqT message) {
      Context previous = context.attach();
      try {
        super.onMessage(message);
      } finally {
        context.detach(previous);
      }
    }

    /**
     * Intercepts onHalfClose() because the application RPC method is called in it. See
     * io.grpc.stub.ServerCalls.UnaryServerCallHandler.UnaryServerCallListener
     */
    @Override
    public void onHalfClose() {
      serverTimeoutManager.withInterruption(context, super::onHalfClose);
    }

    @Override
    public void onCancel() {
      Context previous = context.attach();
      try {
        super.onCancel();
      } finally {
        context.detach(previous);
      }
    }

    @Override
    public void onComplete() {
      Context previous = context.attach();
      try {
        super.onComplete();
      } finally {
        context.detach(previous);
      }
    }

    @Override
    public void onReady() {
      Context previous = context.attach();
      try {
        super.onReady();
      } finally {
        context.detach(previous);
      }
    }
  }
}