package org.telegram.api.engine;

import org.telegram.api.TLApiContext;
import org.telegram.api.TLConfig;
import org.telegram.api.TLDcOption;
import org.telegram.api.auth.TLExportedAuthorization;
import org.telegram.api.engine.storage.ApiState;
import org.telegram.api.engine.storage.AuthKey;
import org.telegram.api.engine.storage.DcInfo;
import org.telegram.api.requests.*;
import org.telegram.api.upload.TLFile;
import org.telegram.mtproto.CallWrapper;
import org.telegram.mtproto.MTProto;
import org.telegram.mtproto.MTProtoCallback;
import org.telegram.mtproto.pq.Authorizer;
import org.telegram.mtproto.pq.PqAuth;
import org.telegram.mtproto.state.MemoryProtoState;
import org.telegram.tl.TLBool;
import org.telegram.tl.TLBoolTrue;
import org.telegram.tl.TLMethod;
import org.telegram.tl.TLObject;
import sun.plugin.dom.exception.InvalidStateException;

import java.io.IOException;
import java.util.*;

/**
 * Created with IntelliJ IDEA.
 * User: ex3ndr
 * Date: 04.11.13
 * Time: 21:54
 */
public class TelegramApi {
    private static final int DEFAULT_TIMEOUT_CHECK = 15000;
    private static final int DEFAULT_TIMEOUT = 15000;
    private static final int FILE_TIMEOUT = 45000;

    private boolean isClosed;

    private int primaryDc;

    private MTProto mainProto;

    private final HashMap<Integer, MTProto> streamingProtos = new HashMap<Integer, MTProto>();
    private final HashMap<Integer, Object> streamingSync = new HashMap<Integer, Object>();

    private ProtoCallback callback;

    private final HashMap<Integer, RpcCallback> callbacks = new HashMap<Integer, RpcCallback>();
    private final HashMap<Integer, TLMethod> requestedMethods = new HashMap<Integer, TLMethod>();

    private TLApiContext apiContext;

    private CallbackThread timeoutThread;
    private final TreeMap<Long, Integer> timeoutTimes = new TreeMap<Long, Integer>();

    private boolean isAuthenticated = false;

    private boolean isRegisteredInApi = false;

    private ApiState state;

    public TelegramApi(ApiState state, int primaryDc) {
        if (state.getAuthKey(primaryDc) == null) {
            throw new InvalidStateException("ApiState might be in authenticated state for primaryDc");
        }
        this.state = state;
        this.primaryDc = primaryDc;
        this.callback = new ProtoCallback();
        this.apiContext = new TLApiContext();
        this.isClosed = false;
        this.timeoutThread = new CallbackThread();
        this.timeoutThread.start();

        AuthKey key = state.getAuthKey(primaryDc);
        DcInfo dc = state.getDcState(primaryDc);

        mainProto = new MTProto(new MemoryProtoState(key.getAuthKey(), dc.getIp(), dc.getPort()), callback,
                new CallWrapper() {
                    @Override
                    public TLObject wrapObject(TLMethod srcRequest) {
                        if (isRegisteredInApi) {
                            return new TLRequestInvokeWithLayer9(srcRequest);
                        }

                        return new TLRequestInvokeWithLayer9(new TLRequestInitConnection(
                                5, "Unkwnon", "10.8.9", "1.0", "ru", srcRequest));
                    }
                });
    }

    public MTProto getMainProto() {
        return mainProto;
    }

    public boolean doSaveFilePart(long _fileId, int _filePart, byte[] _bytes) throws IOException {
        MTProto proto = waitForStreaming(primaryDc);
        TLBool res = doRpcCall(new TLRequestUploadSaveFilePart(_fileId, _filePart, _bytes), FILE_TIMEOUT, proto);
        return res instanceof TLBoolTrue;
    }

    public TLFile doGetFile(int dcId, org.telegram.api.TLAbsInputFileLocation _location, int _offset, int _limit) throws IOException {
        MTProto proto = waitForStreaming(dcId);
        return doRpcCall(new TLRequestUploadGetFile(_location, _offset, _limit), FILE_TIMEOUT, proto);
    }

    private MTProto waitForStreaming(int dcId) throws IOException {
        if (isClosed) {
            throw new TimeoutException();
        }
        if (!isAuthenticated) {
            throw new TimeoutException();
        }

        Object syncObj;
        synchronized (streamingSync) {
            syncObj = streamingSync.get(dcId);
            if (syncObj == null) {
                syncObj = new Object();
                streamingSync.put(dcId, syncObj);
            }
        }

        synchronized (syncObj) {
            MTProto proto;
            synchronized (streamingProtos) {
                proto = streamingProtos.get(dcId);
                if (proto != null) {
                    if (proto.isClosed()) {
                        streamingProtos.remove(dcId);
                        proto = null;
                    }
                }
            }

            if (proto == null) {
                DcInfo dc = state.getDcState(dcId);

                if (dc == null) {
                    TLConfig config = doRpcCall(new TLRequestHelpGetConfig());
                    for (TLDcOption option : config.getDcOptions()) {
                        state.addDcInfo(option.getId(), option.getIpAddress(), option.getPort());
                    }
                    dc = state.getDcState(dcId);
                }

                if (dc == null) {
                    throw new TimeoutException();
                }

                if (dcId != primaryDc) {

                    AuthKey authKey = state.getAuthKey(dcId);

                    if (authKey == null) {
                        Authorizer authorizer = new Authorizer();
                        PqAuth auth = authorizer.doAuth(dc.getIp(), dc.getPort());
                        if (auth == null) {
                            throw new TimeoutException();
                        }
                        state.putNewAuthKey(dcId, auth.getAuthKey(), new byte[8], auth.getServerSalt());
                    }

                    authKey = state.getAuthKey(dcId);

                    if (authKey == null) {
                        throw new TimeoutException();
                    }

                    proto = new MTProto(new MemoryProtoState(authKey.getAuthKey(), dc.getIp(), dc.getPort()), callback,
                            new CallWrapper() {
                                @Override
                                public TLObject wrapObject(TLMethod srcRequest) {
                                    // TODO: Implement
                                    return new TLRequestInvokeWithLayer8(srcRequest);
                                }
                            });

                    TLExportedAuthorization exAuth = doRpcCall(new TLRequestAuthExportAuthorization(dcId));

                    doRpcCall(new TLRequestAuthImportAuthorization(exAuth.getId(), exAuth.getBytes()), DEFAULT_TIMEOUT, proto);

                    streamingProtos.put(dcId, proto);

                    return proto;
                } else {
                    AuthKey authKey = state.getAuthKey(dcId);
                    if (authKey == null) {
                        throw new TimeoutException();
                    }
                    proto = new MTProto(new MemoryProtoState(authKey.getAuthKey(), dc.getIp(), dc.getPort()), callback,
                            new CallWrapper() {
                                @Override
                                public TLObject wrapObject(TLMethod srcRequest) {
                                    // TODO: Implement
                                    return new TLRequestInvokeWithLayer8(srcRequest);
                                }
                            });
                    streamingProtos.put(dcId, proto);
                    return proto;
                }
            }
            return proto;
        }
    }

    public void markAuthenticated() {
        isAuthenticated = true;
    }

    public boolean isAuthenticated() {
        return isAuthenticated;
    }

    public int getPrimaryDc() {
        return primaryDc;
    }

    public boolean isClosed() {
        return isClosed;
    }

    public void close() {
        if (!this.isClosed) {
            this.isClosed = true;
            if (this.timeoutThread != null) {
                this.timeoutThread.interrupt();
                this.timeoutThread = null;
            }
            mainProto.close();
        }
    }

    public <T extends TLObject> void doRpcCall(TLMethod<T> method, RpcCallback<T> callback) {
        doRpcCall(method, DEFAULT_TIMEOUT, callback);
    }

    public <T extends TLObject> void doRpcCall(TLMethod<T> method, int timeout, RpcCallback<T> callback) {
        doRpcCall(method, timeout, callback, mainProto);
    }

    private <T extends TLObject> void doRpcCall(TLMethod<T> method, int timeout, RpcCallback<T> callback, MTProto destProto) {
        if (isClosed) {
            if (callback != null) {
                callback.onError(0, null);
            }
            return;
        }
        synchronized (callbacks) {
            int rpcId = destProto.sendRpcMessage(method, DEFAULT_TIMEOUT);
            if (callback != null) {
                callbacks.put(rpcId, callback);
                requestedMethods.put(rpcId, method);
                long timeoutTime = System.nanoTime() + timeout * 1000 * 1000L;
                synchronized (timeoutTimes) {
                    while (timeoutTimes.containsKey(timeoutTime)) {
                        timeoutTime++;
                    }
                    timeoutTimes.put(timeoutTime, rpcId);
                    timeoutTimes.notifyAll();
                }
            }
        }
    }

    public <T extends TLObject> T doRpcCall(TLMethod<T> method) throws IOException {
        return doRpcCall(method, DEFAULT_TIMEOUT);
    }

    public <T extends TLObject> T doRpcCall(TLMethod<T> method, int timeout) throws IOException {
        return doRpcCall(method, timeout, mainProto);
    }

    public <T extends TLObject> T doRpcCall(TLMethod<T> method, int timeout, MTProto destProto) throws IOException {
        if (isClosed) {
            throw new TimeoutException();
        }
        final Object waitObj = new Object();
        final Object[] res = new Object[3];
        doRpcCall(method, timeout, new RpcCallback<T>() {
            @Override
            public void onResult(T result) {
                synchronized (waitObj) {
                    res[0] = result;
                    res[1] = null;
                    res[2] = null;
                    waitObj.notifyAll();
                }
            }

            @Override
            public void onError(int errorCode, String message) {
                synchronized (waitObj) {
                    res[0] = null;
                    res[1] = errorCode;
                    res[2] = message;
                    waitObj.notifyAll();
                }
            }
        }, destProto);

        synchronized (waitObj) {
            try {
                waitObj.wait(timeout);
            } catch (InterruptedException e) {
                e.printStackTrace();
                throw new TimeoutException();
            }
        }

        if (res[0] == null) {
            if (res[1] != null) {
                Integer code = (Integer) res[1];
                if (code == 0) {
                    throw new TimeoutException();
                } else {
                    throw new RpcException(code, (String) res[2]);
                }
            } else {
                throw new RpcException(0, null);
            }
        } else {
            return (T) res[0];
        }
    }

    private class ProtoCallback implements MTProtoCallback {

        @Override
        public void onSessionCreated(MTProto proto) {
            if (isClosed) {
                return;
            }
            isRegisteredInApi = true;
        }

        @Override
        public void onAuthInvalidated(MTProto proto) {
            close();
        }

        @Override
        public void onApiMessage(byte[] message, MTProto proto) {
            if (isClosed) {
                return;
            }
            isRegisteredInApi = true;
            try {
                TLObject object = apiContext.deserializeMessage(message);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        @Override
        public void onRpcResult(int callId, byte[] response, MTProto proto) {
            if (isClosed) {
                return;
            }
            isRegisteredInApi = true;
            try {
                RpcCallback currentCallback;
                TLMethod method;
                synchronized (callbacks) {
                    currentCallback = callbacks.remove(callId);
                    method = requestedMethods.remove(callId);
                }
                if (currentCallback != null && method != null) {
                    TLObject object = method.deserializeResponse(response, apiContext);
                    currentCallback.onResult(object);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        @Override
        public void onRpcError(int callId, int errorCode, String message, MTProto proto) {
            if (isClosed) {
                return;
            }

            if (errorCode == 400 && message != null && message.startsWith("CONNECTION_NOT_INITED")) {

                System.out.println("Error: CONNECTION_NOT_INITED");

                isRegisteredInApi = false;
                RpcCallback currentCallback;
                TLMethod method;
                synchronized (callbacks) {
                    currentCallback = callbacks.remove(callId);
                    method = requestedMethods.remove(callId);
                }


                if (currentCallback != null && method != null) {
                    // Incorrect timeouts, but this is unreal case and we might at least continue working
                    int rpcId = mainProto.sendRpcMessage(method, DEFAULT_TIMEOUT);
                    callbacks.put(rpcId, currentCallback);
                    requestedMethods.put(rpcId, method);
                    long timeoutTime = System.nanoTime() + DEFAULT_TIMEOUT * 1000 * 1000L;
                    synchronized (timeoutTimes) {
                        while (timeoutTimes.containsKey(timeoutTime)) {
                            timeoutTime++;
                        }
                        timeoutTimes.put(timeoutTime, rpcId);
                        timeoutTimes.notifyAll();
                    }
                }
                return;
            } else {
                isRegisteredInApi = true;
            }

            try {
                RpcCallback currentCallback;
                TLMethod method;
                synchronized (callbacks) {
                    currentCallback = callbacks.remove(callId);
                    method = requestedMethods.remove(callId);
                }
                if (currentCallback != null) {
                    currentCallback.onError(errorCode, message);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private class CallbackThread extends Thread {
        public CallbackThread() {
            setName("Callback#" + hashCode());
        }

        @Override
        public void run() {
            while (!isClosed) {
                Map.Entry<Long, Integer> entry = null;
                synchronized (timeoutTimes) {
                    entry = timeoutTimes.firstEntry();
                    if (entry == null) {
                        try {
                            timeoutTimes.wait(DEFAULT_TIMEOUT_CHECK);
                        } catch (InterruptedException e) {
                            // e.printStackTrace();
                        }
                        continue;
                    }
                    long delta = (entry.getKey() - System.nanoTime()) / (1000 * 1000);
                    if (delta > 0) {
                        try {
                            timeoutTimes.wait(delta);
                        } catch (InterruptedException e) {
                            // e.printStackTrace();
                        }
                        continue;
                    }
                }

                RpcCallback currentCallback;
                synchronized (callbacks) {
                    currentCallback = callbacks.remove(entry.getValue());
                }
                if (currentCallback != null) {
                    currentCallback.onError(0, null);
                }
            }
            synchronized (timeoutTimes) {
                for (Map.Entry<Long, Integer> entry : timeoutTimes.entrySet()) {
                    RpcCallback currentCallback;
                    synchronized (callbacks) {
                        currentCallback = callbacks.remove(entry.getValue());
                    }
                    if (currentCallback != null) {
                        currentCallback.onError(0, null);
                    }
                }
            }
        }
    }
}