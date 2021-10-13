/**
 * Copyright (c) 2013-2021 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.command;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.ReferenceCountUtil;
import org.redisson.RedissonReference;
import org.redisson.SlotCallback;
import org.redisson.api.RFuture;
import org.redisson.cache.LRUCacheMap;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisException;
import org.redisson.client.RedisRedirectException;
import org.redisson.client.RedisTimeoutException;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommand;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.config.MasterSlaveServersConfig;
import org.redisson.connection.ConnectionManager;
import org.redisson.connection.MasterSlaveEntry;
import org.redisson.connection.NodeSource;
import org.redisson.liveobject.core.RedissonObjectBuilder;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedissonPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 *
 * @author Nikita Koksharov
 *
 */
public class CommandAsyncService implements CommandAsyncExecutor {

    static final Logger log = LoggerFactory.getLogger(CommandAsyncService.class);

    final ConnectionManager connectionManager;
    final RedissonObjectBuilder objectBuilder;
    final RedissonObjectBuilder.ReferenceType referenceType;

    public CommandAsyncService(ConnectionManager connectionManager, RedissonObjectBuilder objectBuilder, RedissonObjectBuilder.ReferenceType referenceType) {
        this.connectionManager = connectionManager;
        this.objectBuilder = objectBuilder;
        this.referenceType = referenceType;
    }

    @Override
    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    private boolean isRedissonReferenceSupportEnabled() {
        return objectBuilder != null;
    }

    @Override
    public void syncSubscription(RFuture<?> future) {
        MasterSlaveServersConfig config = connectionManager.getConfig();
        try {
            int timeout = config.getTimeout() + config.getRetryInterval() * config.getRetryAttempts();
            if (!future.await(timeout)) {
                ((RPromise<?>) future).tryFailure(new RedisTimeoutException("Subscribe timeout: (" + timeout + "ms). Increase 'subscriptionsPerConnection' and/or 'subscriptionConnectionPoolSize' parameters."));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        future.syncUninterruptibly();
    }

    @Override
    public void syncSubscriptionInterrupted(RFuture<?> future) throws InterruptedException {
        MasterSlaveServersConfig config = connectionManager.getConfig();
        int timeout = config.getTimeout() + config.getRetryInterval() * config.getRetryAttempts();
        if (!future.await(timeout)) {
            ((RPromise<?>) future).tryFailure(new RedisTimeoutException("Subscribe timeout: (" + timeout + "ms). Increase 'subscriptionsPerConnection' and/or 'subscriptionConnectionPoolSize' parameters."));
        }
        future.sync();
    }

    @Override
    public <V> V get(RFuture<V> future) {
        if (Thread.currentThread().getName().startsWith("redisson-netty")) {
            throw new IllegalStateException("Sync methods can't be invoked from async/rx/reactive listeners");
        }

        try {
            future.await();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RedisException(e);
        }
        if (future.isSuccess()) {
            return future.getNow();
        }

        throw convertException(future);
    }
    
    @Override
    public <V> V getInterrupted(RFuture<V> future) throws InterruptedException {
        try {
            future.await();
        } catch (InterruptedException e) {
            ((RPromise) future).tryFailure(e);
            throw e;
        }

        if (future.isSuccess()) {
            return future.getNow();
        }

        throw convertException(future);
    }

    protected <R> RPromise<R> createPromise() {
        return new RedissonPromise<R>();
    }

    @Override
    public <T, R> RFuture<R> readAsync(RedisClient client, MasterSlaveEntry entry, Codec codec, RedisCommand<T> command, Object... params) {
        RPromise<R> mainPromise = createPromise();
        async(true, new NodeSource(entry, client), codec, command, params, mainPromise, false, false);
        return mainPromise;
    }
    
    @Override
    public <T, R> RFuture<R> readAsync(RedisClient client, String name, Codec codec, RedisCommand<T> command, Object... params) {
        RPromise<R> mainPromise = createPromise();
        int slot = connectionManager.calcSlot(name);
        async(true, new NodeSource(slot, client), codec, command, params, mainPromise, false, false);
        return mainPromise;
    }
    
    public <T, R> RFuture<R> readAsync(RedisClient client, byte[] key, Codec codec, RedisCommand<T> command, Object... params) {
        RPromise<R> mainPromise = createPromise();
        int slot = connectionManager.calcSlot(key);
        async(true, new NodeSource(slot, client), codec, command, params, mainPromise, false, false);
        return mainPromise;
    }

    @Override
    public <T, R> RFuture<R> readAsync(RedisClient client, Codec codec, RedisCommand<T> command, Object... params) {
        RPromise<R> mainPromise = createPromise();
        async(true, new NodeSource(client), codec, command, params, mainPromise, false, false);
        return mainPromise;
    }

    @Override
    public <T, R> RFuture<Collection<R>> readAllAsync(Codec codec, RedisCommand<T> command, Object... params) {
        List<R> results = new ArrayList<R>();
        return readAllAsync(results, codec, command, params);
    }
    
    @Override
    public <T, R> RFuture<Collection<R>> readAllAsync(RedisCommand<T> command, Object... params) {
        List<R> results = new ArrayList<R>();
        return readAllAsync(results, connectionManager.getCodec(), command, params);
    }
    
    @Override
    public <T, R> RFuture<Collection<R>> readAllAsync(Collection<R> results, Codec codec, RedisCommand<T> command, Object... params) {
        RPromise<Collection<R>> mainPromise = createPromise();
        Collection<MasterSlaveEntry> nodes = connectionManager.getEntrySet();
        AtomicInteger counter = new AtomicInteger(nodes.size());
        BiConsumer<Object, Throwable> listener = new BiConsumer<Object, Throwable>() {
            @Override
            public void accept(Object result, Throwable u) {
                if (u != null && !(u instanceof RedisRedirectException)) {
                    mainPromise.tryFailure(u);
                    return;
                }
                
                if (result instanceof Collection) {
                    synchronized (results) {
                        results.addAll((Collection) result);
                    }
                } else {
                    synchronized (results) {
                        results.add((R) result);
                    }
                }
                
                if (counter.decrementAndGet() == 0
                        && !mainPromise.isDone()) {
                    mainPromise.trySuccess(results);
                }
            }
        };

        for (MasterSlaveEntry entry : nodes) {
            RPromise<R> promise = new RedissonPromise<R>();
            promise.onComplete(listener);
            async(true, new NodeSource(entry), codec, command, params, promise, true, false);
        }
        return mainPromise;
    }
    
    @Override
    public <T, R> RFuture<R> readRandomAsync(Codec codec, RedisCommand<T> command, Object... params) {
        RPromise<R> mainPromise = createPromise();
        List<MasterSlaveEntry> nodes = new ArrayList<MasterSlaveEntry>(connectionManager.getEntrySet());
        Collections.shuffle(nodes);

        retryReadRandomAsync(codec, command, mainPromise, nodes, params);
        return mainPromise;
    }

    @Override
    public <T, R> RFuture<R> readRandomAsync(MasterSlaveEntry entry, Codec codec, RedisCommand<T> command, Object... params) {
        RPromise<R> mainPromise = createPromise();
        retryReadRandomAsync(codec, command, mainPromise, Collections.singletonList(entry), params);
        return mainPromise;
    }
    
    private <R, T> void retryReadRandomAsync(Codec codec, RedisCommand<T> command, RPromise<R> mainPromise,
            List<MasterSlaveEntry> nodes, Object... params) {
        RPromise<R> attemptPromise = new RedissonPromise<R>();
        attemptPromise.onComplete((res, e) -> {
            if (e == null) {
                if (res == null) {
                    if (nodes.isEmpty()) {
                        mainPromise.trySuccess(null);
                    } else {
                        retryReadRandomAsync(codec, command, mainPromise, nodes, params);
                    }
                } else {
                    mainPromise.trySuccess(res);
                }
            } else {
                mainPromise.tryFailure(e);
            }
        });

        MasterSlaveEntry entry = nodes.remove(0);
        async(true, new NodeSource(entry), codec, command, params, attemptPromise, false, false);
    }

    @Override
    public <T> RFuture<Void> writeAllAsync(RedisCommand<T> command, Object... params) {
        return writeAllAsync(command, null, params);
    }

    @Override
    public <R, T> RFuture<R> writeAllAsync(RedisCommand<T> command, SlotCallback<T, R> callback, Object... params) {
        return allAsync(false, connectionManager.getCodec(), command, callback, params);
    }

    @Override
    public <R, T> RFuture<R> writeAllAsync(Codec codec, RedisCommand<T> command, SlotCallback<T, R> callback, Object... params) {
        return allAsync(false, codec, command, callback, params);
    }
    
    @Override
    public <R, T> RFuture<R> readAllAsync(RedisCommand<T> command, SlotCallback<T, R> callback, Object... params) {
        return allAsync(true, connectionManager.getCodec(), command, callback, params);
    }

    private <T, R> RFuture<R> allAsync(boolean readOnlyMode, Codec codec, RedisCommand<T> command, SlotCallback<T, R> callback, Object... params) {
        RPromise<R> mainPromise = new RedissonPromise<R>();
        Collection<MasterSlaveEntry> nodes = connectionManager.getEntrySet();
        AtomicInteger counter = new AtomicInteger(nodes.size());
        BiConsumer<T, Throwable> listener = new BiConsumer<T, Throwable>() {
            @Override
            public void accept(T result, Throwable u) {
                if (u != null && !(u instanceof RedisRedirectException)) {
                    mainPromise.tryFailure(u);
                    return;
                }
                
                if (u instanceof RedisRedirectException) {
                    result = command.getConvertor().convert(result);
                }
                
                if (callback != null) {
                    callback.onSlotResult(result);
                }
                if (counter.decrementAndGet() == 0) {
                    if (callback != null) {
                        mainPromise.trySuccess(callback.onFinish());
                    } else {
                        mainPromise.trySuccess(null);
                    }
                }
            }
        };

        for (MasterSlaveEntry entry : nodes) {
            RPromise<T> promise = new RedissonPromise<T>();
            promise.onComplete(listener);
            async(readOnlyMode, new NodeSource(entry), codec, command, params, promise, true, false);
        }
        return mainPromise;
    }

    public <V> RedisException convertException(RFuture<V> future) {
        if (future.cause() instanceof RedisException) {
            return (RedisException) future.cause();
        }
        return new RedisException("Unexpected exception while processing command", future.cause());
    }

    private NodeSource getNodeSource(String key) {
        int slot = connectionManager.calcSlot(key);
        return new NodeSource(slot);
    }

    private NodeSource getNodeSource(byte[] key) {
        int slot = connectionManager.calcSlot(key);
        return new NodeSource(slot);
    }
    
    @Override
    public <T, R> RFuture<R> readAsync(String key, Codec codec, RedisCommand<T> command, Object... params) {
        RPromise<R> mainPromise = createPromise();
        NodeSource source = getNodeSource(key);
        async(true, source, codec, command, params, mainPromise, false, false);
        return mainPromise;
    }
    
    @Override
    public <T, R> RFuture<R> readAsync(byte[] key, Codec codec, RedisCommand<T> command, Object... params) {
        RPromise<R> mainPromise = createPromise();
        NodeSource source = getNodeSource(key);
        async(true, source, codec, command, params, mainPromise, false, false);
        return mainPromise;
    }

    public <T, R> RFuture<R> readAsync(MasterSlaveEntry entry, Codec codec, RedisCommand<T> command, Object... params) {
        RPromise<R> mainPromise = createPromise();
        async(true, new NodeSource(entry), codec, command, params, mainPromise, false, false);
        return mainPromise;
    }

    @Override
    public <T, R> RFuture<R> writeAsync(RedisClient client, Codec codec, RedisCommand<T> command, Object... params) {
        MasterSlaveEntry entry = getConnectionManager().getEntry(client);
        return writeAsync(entry, codec, command, params);
    }

    @Override
    public <T, R> RFuture<R> writeAsync(MasterSlaveEntry entry, Codec codec, RedisCommand<T> command, Object... params) {
        RPromise<R> mainPromise = createPromise();
        async(false, new NodeSource(entry), codec, command, params, mainPromise, false, false);
        return mainPromise;
    }

    @Override
    public <T, R> RFuture<R> readAsync(String key, RedisCommand<T> command, Object... params) {
        return readAsync(key, connectionManager.getCodec(), command, params);
    }

    @Override
    public <T, R> RFuture<R> evalReadAsync(String key, Codec codec, RedisCommand<T> evalCommandType, String script, List<Object> keys, Object... params) {
        NodeSource source = getNodeSource(key);
        return evalAsync(source, true, codec, evalCommandType, script, keys, false, params);
    }

    @Override
    public <T, R> RFuture<R> evalReadAsync(MasterSlaveEntry entry, Codec codec, RedisCommand<T> evalCommandType, String script, List<Object> keys, Object... params) {
        return evalAsync(new NodeSource(entry), true, codec, evalCommandType, script, keys, false, params);
    }

    @Override
    public <T, R> RFuture<R> evalReadAsync(RedisClient client, String name, Codec codec, RedisCommand<T> evalCommandType, String script, List<Object> keys, Object... params) {
        int slot = connectionManager.calcSlot(name);
        return evalAsync(new NodeSource(slot, client), true, codec, evalCommandType, script, keys, false, params);
    }

    @Override
    public <T, R> RFuture<R> evalWriteAsync(String key, Codec codec, RedisCommand<T> evalCommandType, String script, List<Object> keys, Object... params) {
        NodeSource source = getNodeSource(key);
        return evalAsync(source, false, codec, evalCommandType, script, keys, false, params);
    }

    @Override
    public <T, R> RFuture<R> evalWriteNoRetryAsync(String key, Codec codec, RedisCommand<T> evalCommandType, String script, List<Object> keys, Object... params) {
        NodeSource source = getNodeSource(key);
        return evalAsync(source, false, codec, evalCommandType, script, keys, true, params);
    }

    public <T, R> RFuture<R> evalWriteAsync(MasterSlaveEntry entry, Codec codec, RedisCommand<T> evalCommandType, String script, List<Object> keys, Object... params) {
        return evalAsync(new NodeSource(entry), false, codec, evalCommandType, script, keys, false, params);
    }

    @Override
    public <T, R> RFuture<R> evalWriteAllAsync(RedisCommand<T> command, SlotCallback<T, R> callback, String script, List<Object> keys, Object... params) {
        return evalAllAsync(false, command, callback, script, keys, params);
    }

    public <T, R> RFuture<R> evalAllAsync(boolean readOnlyMode, RedisCommand<T> command, SlotCallback<T, R> callback, String script, List<Object> keys, Object... params) {
        RPromise<R> mainPromise = new RedissonPromise<R>();
        Collection<MasterSlaveEntry> entries = connectionManager.getEntrySet();
        AtomicInteger counter = new AtomicInteger(entries.size());
        BiConsumer<T, Throwable> listener = new BiConsumer<T, Throwable>() {
            @Override
            public void accept(T t, Throwable u) {
                if (u != null && !(u instanceof RedisRedirectException)) {
                    mainPromise.tryFailure(u);
                    return;
                }
                
                callback.onSlotResult(t);
                if (counter.decrementAndGet() == 0
                        && !mainPromise.isDone()) {
                    mainPromise.trySuccess(callback.onFinish());
                }
            }
        };

        List<Object> args = new ArrayList<Object>(2 + keys.size() + params.length);
        args.add(script);
        args.add(keys.size());
        args.addAll(keys);
        args.addAll(Arrays.asList(params));
        for (MasterSlaveEntry entry : entries) {
            RPromise<T> promise = new RedissonPromise<T>();
            promise.onComplete(listener);
            async(readOnlyMode, new NodeSource(entry), connectionManager.getCodec(), command, args.toArray(), promise, true, false);
        }
        return mainPromise;
    }

    private RFuture<String> loadScript(RedisClient client, String script) {
        MasterSlaveEntry entry = getConnectionManager().getEntry(client);
        if (entry.getClient().equals(client)) {
            return writeAsync(entry, StringCodec.INSTANCE, RedisCommands.SCRIPT_LOAD, script);
        }
        return readAsync(client, StringCodec.INSTANCE, RedisCommands.SCRIPT_LOAD, script);
    }
    
    protected boolean isEvalCacheActive() {
        return getConnectionManager().getCfg().isUseScriptCache();
    }
    
    private static final Map<String, String> SHA_CACHE = new LRUCacheMap<String, String>(500, 0, 0);
    
    private String calcSHA(String script) {
        String digest = SHA_CACHE.get(script);
        if (digest == null) {
            try {
                MessageDigest mdigest = MessageDigest.getInstance("SHA-1");
                byte[] s = mdigest.digest(script.getBytes());
                digest = ByteBufUtil.hexDump(s);
                SHA_CACHE.put(script, digest);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return digest;
    }
    
    private Object[] copy(Object[] params) {
        List<Object> result = new ArrayList<Object>();
        for (Object object : params) {
            if (object instanceof ByteBuf) {
                ByteBuf b = (ByteBuf) object;
                ByteBuf nb = ByteBufAllocator.DEFAULT.buffer(b.readableBytes());
                int ri = b.readerIndex();
                nb.writeBytes(b);
                b.readerIndex(ri);
                result.add(nb);
            } else {
                result.add(object);
            }
        }
        return result.toArray();
    }
    
    private <T, R> RFuture<R> evalAsync(NodeSource nodeSource, boolean readOnlyMode, Codec codec, RedisCommand<T> evalCommandType,
                                        String script, List<Object> keys, boolean noRetry, Object... params) {
        if (isEvalCacheActive() && evalCommandType.getName().equals("EVAL")) {
            RPromise<R> mainPromise = new RedissonPromise<R>();
            
            Object[] pps = copy(params);
            
            RPromise<R> promise = new RedissonPromise<R>();
            String sha1 = calcSHA(script);
            RedisCommand cmd = new RedisCommand(evalCommandType, "EVALSHA");
            List<Object> args = new ArrayList<Object>(2 + keys.size() + params.length);
            args.add(sha1);
            args.add(keys.size());
            args.addAll(keys);
            args.addAll(Arrays.asList(params));

            RedisExecutor<T, R> executor = new RedisExecutor<>(readOnlyMode, nodeSource, codec, cmd,
                                                        args.toArray(), promise, false,
                                                        connectionManager, objectBuilder, referenceType, noRetry);
            executor.execute();

            promise.onComplete((res, e) -> {
                if (e != null) {
                    if (e.getMessage().startsWith("NOSCRIPT")) {
                        RFuture<String> loadFuture = loadScript(executor.getRedisClient(), script);
                        loadFuture.onComplete((r, ex) -> {
                            if (ex != null) {
                                free(pps);
                                mainPromise.tryFailure(ex);
                                return;
                            }

                            RedisCommand command = new RedisCommand(evalCommandType, "EVALSHA");
                            List<Object> newargs = new ArrayList<Object>(2 + keys.size() + params.length);
                            newargs.add(sha1);
                            newargs.add(keys.size());
                            newargs.addAll(keys);
                            newargs.addAll(Arrays.asList(pps));

                            NodeSource ns = nodeSource;
                            if (ns.getRedisClient() == null) {
                                ns = new NodeSource(nodeSource, executor.getRedisClient());
                            }

                            async(readOnlyMode, ns, codec, command, newargs.toArray(), mainPromise, false, noRetry);
                        });
                    } else {
                        free(pps);
                        mainPromise.tryFailure(e);
                    }
                    return;
                }
                free(pps);
                mainPromise.trySuccess(res);
            });
            return mainPromise;
        }
        
        RPromise<R> mainPromise = createPromise();
        List<Object> args = new ArrayList<Object>(2 + keys.size() + params.length);
        args.add(script);
        args.add(keys.size());
        args.addAll(keys);
        args.addAll(Arrays.asList(params));
        async(readOnlyMode, nodeSource, codec, evalCommandType, args.toArray(), mainPromise, false, noRetry);
        return mainPromise;
    }

    @Override
    public <T, R> RFuture<R> writeAsync(String key, RedisCommand<T> command, Object... params) {
        return writeAsync(key, connectionManager.getCodec(), command, params);
    }

    @Override
    public <T, R> RFuture<R> writeAsync(String key, Codec codec, RedisCommand<T> command, Object... params) {
        RPromise<R> mainPromise = createPromise();
        NodeSource source = getNodeSource(key);
        async(false, source, codec, command, params, mainPromise, false, false);
        return mainPromise;
    }

    public <T, R> RFuture<R> writeAsync(byte[] key, Codec codec, RedisCommand<T> command, Object... params) {
        RPromise<R> mainPromise = createPromise();
        NodeSource source = getNodeSource(key);
        async(false, source, codec, command, params, mainPromise, false, false);
        return mainPromise;
    }
    
    public <V, R> void async(boolean readOnlyMode, NodeSource source, Codec codec,
            RedisCommand<V> command, Object[] params, RPromise<R> mainPromise, 
            boolean ignoreRedirect, boolean noRetry) {
        RedisExecutor<V, R> executor = new RedisExecutor<>(readOnlyMode, source, codec, command, params, mainPromise,
                                                    ignoreRedirect, connectionManager, objectBuilder, referenceType, noRetry);
        executor.execute();
    }

    private void free(Object[] params) {
        for (Object obj : params) {
            ReferenceCountUtil.safeRelease(obj);
        }
    }
    
    @Override
    public <T, R> RFuture<R> readBatchedAsync(Codec codec, RedisCommand<T> command, SlotCallback<T, R> callback, String... keys) {
        return executeBatchedAsync(true, codec, command, callback, keys, null);
    }

    @Override
    public <T, R> RFuture<R> writeBatchedAsync(Codec codec, RedisCommand<T> command, SlotCallback<T, R> callback, String... keys) {
        return executeBatchedAsync(false, codec, command, callback, keys, null);
    }
    
    @Override
    public <T, R> RFuture<R> writeBatchedAsync(Codec codec, RedisCommand<T> command, SlotCallback<T, R> callback, String[] keys, Map<String, ?> valueMap) {
        return executeBatchedAsync(false, codec, command, callback, keys, valueMap);
    }
    
    private <T, R> RFuture<R> executeBatchedAsync(boolean readOnly, Codec codec, RedisCommand<T> command, SlotCallback<T, R> callback, String[] keys, Map<String, ?> valueMap) {
        if (!connectionManager.isClusterMode()) {
            List<Object> params = null;
            if (valueMap != null) {
                params = new ArrayList<>(keys.length * 2);
                for (String key : keys) {
                    params.add(key);
                    params.add(valueMap.get(key));
                }
            } else {
                params = Arrays.asList(keys);
            }
            if (readOnly) {
                return readAsync((String) null, codec, command, params.toArray());
            }
            return writeAsync((String) null, codec, command, params.toArray());
        }

        Map<MasterSlaveEntry, Map<Integer, List<String>>> entry2keys = Arrays.stream(keys).collect(
                Collectors.groupingBy(k -> {
                    int slot = connectionManager.calcSlot(k);
                    return connectionManager.getEntry(slot);
                }, Collectors.groupingBy(k -> {
                    return connectionManager.calcSlot(k);
                        }, Collectors.toList())));

        long total = entry2keys.values().stream().mapToInt(m -> m.size()).sum();

        RPromise<R> result = new RedissonPromise<>();
        AtomicLong executed = new AtomicLong(total);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        BiConsumer<T, Throwable> listener = (res, ex) -> {
            if (ex != null) {
                failed.set(ex);
            } else {
                if (res != null) {
                    callback.onSlotResult(res);
                }
            }

            if (executed.decrementAndGet() == 0) {
                if (failed.get() != null) {
                    result.tryFailure(failed.get());
                } else {
                    result.trySuccess(callback.onFinish());
                }
            }
        };

        for (Entry<MasterSlaveEntry, Map<Integer, List<String>>> entry : entry2keys.entrySet()) {
            // executes in batch due to CROSSLOT error
            CommandBatchService executorService;
            if (this instanceof CommandBatchService) {
                executorService = (CommandBatchService) this;
            } else {
                executorService = new CommandBatchService(this);
            }

            for (List<String> groupedKeys : entry.getValue().values()) {
                RedisCommand<T> c = command;
                RedisCommand<T> newCommand = callback.createCommand(groupedKeys);
                if (newCommand != null) {
                    c = newCommand;
                }
                if (readOnly) {
                    RFuture<T> f = executorService.readAsync(entry.getKey(), codec, c, callback.createParams(groupedKeys));
                    f.onComplete(listener);
                } else {
                    RFuture<T> f = executorService.writeAsync(entry.getKey(), codec, c, callback.createParams(groupedKeys));
                    f.onComplete(listener);
                }
            }

            if (!(this instanceof CommandBatchService)) {
                executorService.executeAsync();
            }
        }

        return result;
    }

    
    @Override
    public RedissonObjectBuilder getObjectBuilder() {
        return objectBuilder;
    }

    @Override
    public ByteBuf encode(Codec codec, Object value) {
        if (isRedissonReferenceSupportEnabled()) {
            RedissonReference reference = getObjectBuilder().toReference(value);
            if (reference != null) {
                value = reference;
            }
        }

        try {
            return codec.getValueEncoder().encode(value);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public ByteBuf encodeMapKey(Codec codec, Object value) {
        if (isRedissonReferenceSupportEnabled()) {
            RedissonReference reference = getObjectBuilder().toReference(value);
            if (reference != null) {
                value = reference;
            }
        }

        try {
            return codec.getMapKeyEncoder().encode(value);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public ByteBuf encodeMapValue(Codec codec, Object value) {
        if (isRedissonReferenceSupportEnabled()) {
            RedissonReference reference = getObjectBuilder().toReference(value);
            if (reference != null) {
                value = reference;
            }
        }

        try {
            return codec.getMapValueEncoder().encode(value);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public <V> RFuture<V> pollFromAnyAsync(String name, Codec codec, RedisCommand<Object> command, long secondsTimeout, String... queueNames) {
        if (connectionManager.isClusterMode() && queueNames.length > 0) {
            RPromise<V> result = new RedissonPromise<V>();
            AtomicReference<Iterator<String>> ref = new AtomicReference<Iterator<String>>();
            List<String> names = new ArrayList<String>();
            names.add(name);
            names.addAll(Arrays.asList(queueNames));
            ref.set(names.iterator());
            AtomicLong counter = new AtomicLong(secondsTimeout);
            poll(name, codec, result, ref, names, counter, command);
            return result;
        } else {
            List<Object> params = new ArrayList<Object>(queueNames.length + 1);
            params.add(name);
            params.addAll(Arrays.asList(queueNames));
            params.add(secondsTimeout);
            return writeAsync(name, codec, command, params.toArray());
        }
    }

    private <V> void poll(String name, Codec codec, RPromise<V> result, AtomicReference<Iterator<String>> ref, 
            List<String> names, AtomicLong counter, RedisCommand<Object> command) {
        if (ref.get().hasNext()) {
            String currentName = ref.get().next().toString();
            RFuture<V> future = writeAsync(currentName, codec, command, currentName, 1);
            future.onComplete((res, e) -> {
                if (e != null) {
                    result.tryFailure(e);
                    return;
                }
                
                if (res != null) {
                    result.trySuccess(res);
                } else {
                    if (counter.decrementAndGet() == 0) {
                        result.trySuccess(null);
                        return;
                    }
                    poll(name, codec, result, ref, names, counter, command);
                }
            });
        } else {
            ref.set(names.iterator());
            poll(name, codec, result, ref, names, counter, command);
        }
    }
    
}
