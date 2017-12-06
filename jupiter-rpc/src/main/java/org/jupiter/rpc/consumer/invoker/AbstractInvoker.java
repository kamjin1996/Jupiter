package org.jupiter.rpc.consumer.invoker;

import org.jupiter.rpc.*;
import org.jupiter.rpc.consumer.cluster.ClusterInvoker;
import org.jupiter.rpc.consumer.dispatcher.Dispatcher;
import org.jupiter.rpc.consumer.future.InvokeFuture;
import org.jupiter.rpc.model.metadata.ClusterStrategyConfig;
import org.jupiter.rpc.model.metadata.MessageWrapper;
import org.jupiter.rpc.model.metadata.MethodSpecialConfig;
import org.jupiter.rpc.model.metadata.ServiceMetadata;

import java.util.List;

/**
 * jupiter
 * org.jupiter.rpc.consumer.invoker
 *
 * @author jiachun.fjc
 */
public abstract class AbstractInvoker {

    private final JClient client;
    private final ServiceMetadata metadata; // 目标服务元信息
    private final ClusterStrategyBridging clusterStrategyBridging;

    public AbstractInvoker(JClient client,
                           ServiceMetadata metadata,
                           Dispatcher dispatcher,
                           ClusterStrategyConfig defaultStrategy,
                           List<MethodSpecialConfig> methodSpecialConfigs) {
        this.client = client;
        this.metadata = metadata;
        clusterStrategyBridging = new ClusterStrategyBridging(client, dispatcher, defaultStrategy, methodSpecialConfigs);
    }

    protected JClient getClient() {
        return client;
    }

    protected ServiceMetadata getMetadata() {
        return metadata;
    }

    protected ClusterStrategyBridging getClusterStrategyBridging() {
        return clusterStrategyBridging;
    }

    protected ClusterInvoker findClusterInvoker(String methodName) {
        return clusterStrategyBridging.getClusterInvoker(methodName);
    }

    protected JRequest createRequest(String methodName, Object[] args) {
        MessageWrapper message = new MessageWrapper(metadata);
        message.setAppName(client.appName());
        message.setMethodName(methodName);
        // 不需要方法参数类型, 服务端会根据args具体类型按照JLS规则动态dispatch
        message.setArgs(args);

        JRequest request = new JRequest();
        request.message(message);

        return request;
    }

    static class Context implements JFilterContext {

        private final ClusterInvoker invoker;
        private final Class<?> returnType;
        private final boolean sync;
        private Object result;

        Context(ClusterInvoker invoker, Class<?> returnType, boolean sync) {
            this.invoker = invoker;
            this.returnType = returnType;
            this.sync = sync;
        }

        @Override
        public JFilter.Type getType() {
            return JFilter.Type.CONSUMER;
        }

        public ClusterInvoker getInvoker() {
            return invoker;
        }

        public Class<?> getReturnType() {
            return returnType;
        }

        public boolean isSync() {
            return sync;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }
    }

    static class ClusterInvokeFilter implements JFilter {

        @Override
        public Type getType() {
            return JFilter.Type.CONSUMER;
        }

        @Override
        public <T extends JFilterContext> void doFilter(JRequest request, T filterCtx, JFilterChain next) throws Throwable {
            Context invokeCtx = (Context) filterCtx;
            ClusterInvoker invoker = invokeCtx.getInvoker();
            Class<?> returnType = invokeCtx.getReturnType();
            // invoke
            InvokeFuture<?> future = invoker.invoke(request, returnType);

            if (invokeCtx.isSync()) {
                invokeCtx.setResult(future.getResult());
            } else {
                invokeCtx.setResult(future);
            }
        }
    }

    static class Chains {

        private static final JFilterChain headChain;

        static {
            JFilterChain invokeChain = new DefaultFilterChain(new ClusterInvokeFilter(), null);
            headChain = JFilterLoader.loadExtFilters(invokeChain, JFilter.Type.CONSUMER);
        }

        static <T extends JFilterContext> T invoke(JRequest request, T invokeCtx) throws Throwable {
            headChain.doFilter(request, invokeCtx);
            return invokeCtx;
        }
    }
}
