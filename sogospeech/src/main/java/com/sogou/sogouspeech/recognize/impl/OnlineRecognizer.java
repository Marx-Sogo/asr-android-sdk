package com.sogou.sogouspeech.recognize.impl;

import android.text.TextUtils;
import android.util.Log;

import com.google.protobuf.ByteString;
import com.sogou.sogocommon.ErrorIndex;
import com.sogou.sogocommon.utils.HttpsUtil;
import com.sogou.sogocommon.utils.ShortByteUtil;
import com.sogou.sogocommon.utils.SogoConstants;
import com.sogou.sogocommon.utils.LogUtil;
import com.sogou.sogouspeech.EventListener;
import com.sogou.sogouspeech.paramconstants.SpeechConstants;
import com.sogou.sogouspeech.recognize.IAudioRecognizer;
import com.sogou.sogouspeech.recognize.bean.SogoASRConfig;
import com.sogou.speech.asr.v1.RecognitionConfig;
import com.sogou.speech.asr.v1.SpeechRecognitionAlternative;
import com.sogou.speech.asr.v1.StreamingRecognitionConfig;
import com.sogou.speech.asr.v1.StreamingRecognitionResult;
import com.sogou.speech.asr.v1.StreamingRecognizeRequest;
import com.sogou.speech.asr.v1.StreamingRecognizeResponse;
import com.sogou.speech.asr.v1.asrGrpc;


import java.util.HashMap;

import io.grpc.ManagedChannel;
import io.grpc.okhttp.NegotiationType;
import io.grpc.okhttp.OkHttpChannelProvider;
import io.grpc.stub.StreamObserver;

public class OnlineRecognizer extends IAudioRecognizer {
    private static final String TAG = OnlineRecognizer.class.getSimpleName();

    private SogoASRConfig.ASRSettings mAsrSettings = null;
    private EventListener mListener = null;
    private asrGrpc.asrStub client;
    private StreamObserver<StreamingRecognizeRequest> mRequestObserver;


    private volatile boolean isCompleted = false;

    public OnlineRecognizer(EventListener mListener) {
        this.mListener = mListener;
    }

    @Override
    public void initListening(SogoASRConfig.SogoSettings asrSettings) {
        if (null == asrSettings) {
            Log.e(SpeechConstants.CommonTag, "asrSettings is null");
            return;
        }

        mAsrSettings = (SogoASRConfig.ASRSettings) asrSettings;
        createGrpcClient();
    }

    @Override
    public void startListening(String languageCode) {
        if(!TextUtils.isEmpty(languageCode)){
            mAsrSettings.setLanguageCode(languageCode);
        }
        isCompleted = false;
        buildGrpcConnection();
    }

    @Override
    public void stopListening() {
//        LogUtil.d(TAG, "recognize.onCompleted");
        if(isCompleted){
            return;
        }
        if(mRequestObserver == null){
            return;
        }
        mRequestObserver.onCompleted();
        isCompleted = true;
    }

    @Override
    public void cancelListening() {
        finishRecognizing();
    }

    @Override
    public void destroy() {
        finishRecognizing();
//        mResponseObserver = null;
        client = null;
    }

    @Override
    public void feedShortData(int sn, short[] data) {
//        LogUtil.d(TAG, "feed short data(short) length : " + data.length);
        byte[] dateBytes = ShortByteUtil.shortArray2ByteArray(data);
        recognize(dateBytes,data.length,sn);
        dateBytes = null;
    }

    @Override
    public void feedByteData(int sn, byte[] data) {
//        LogUtil.d(TAG, "feed short data(short) length : " + data.length);
        recognize(data,data.length,sn);
    }

    private void createGrpcClient() {
        HashMap<String, String> headerParams = new HashMap<>();
        headerParams.put("Authorization", "Bearer " + mAsrSettings.getToken());
        headerParams.put("appid", ""+mAsrSettings.getAppid());
        headerParams.put("uuid", ""+mAsrSettings.getUuid());

        Log.d(TAG, "create rpc client : " + mAsrSettings);
        final ManagedChannel channel = new OkHttpChannelProvider()
                .builderForAddress(SogoConstants.URL_CONSTANT.URL_RECOGNIZE,
                        443)
                .overrideAuthority(SogoConstants.URL_CONSTANT.URL_RECOGNIZE
                        + ":443")
                .negotiationType(NegotiationType.TLS)
                .sslSocketFactory(HttpsUtil.getSSLSocketFactory(null, null, null))
                .intercept(new HeaderClientInterceptor(headerParams))
                .build();
        client = asrGrpc.newStub(channel);

//        headerParams.clear();
//        headerParams = null;
    }

    private void buildGrpcConnection() {
        if (client == null) {
            if (mListener != null) {
                mListener.onError(SpeechConstants.ErrorDomain.ERR_ASR_ONLINE_ENGINE_LOGIC, ErrorIndex.ERROR_NETWORK_OTHER, "client == null",null);
            }
            return;
        }
        if(mResponseObserver == null){
            if (mListener != null) {
                mListener.onError(SpeechConstants.ErrorDomain.ERR_ASR_ONLINE_ENGINE_LOGIC, ErrorIndex.ERROR_NETWORK_OTHER, "mResponseObserver == null",null);
            }
            return;
        }
        mRequestObserver = client.streamingRecognize(mResponseObserver);
        mRequestObserver.onNext(StreamingRecognizeRequest.newBuilder()
                .setStreamingConfig(StreamingRecognitionConfig.newBuilder()
                        .setConfig(RecognitionConfig.newBuilder()
                                .setLanguageCode(mAsrSettings.getLanguageCode())
                                .setEncoding(mAsrSettings.getAudioEncoding())
                                .setSampleRateHertz(16000)
                                .setEnableWordTimeOffsets(false)
                                .setMaxAlternatives(1)
                                .setProfanityFilter(true)
                                .setDisableAutomaticPunctuation(false)
                                .setModel(mAsrSettings.getModel())
                                .build())
                        .setInterimResults(true)
                        .setSingleUtterance(true)
                        .build())
                .build());
        Log.d(TAG, "build rpc connection : " + mAsrSettings);
    }

    private StreamObserver<StreamingRecognizeResponse> mResponseObserver = new StreamObserver<StreamingRecognizeResponse>() {
        @Override
        public void onNext(StreamingRecognizeResponse response) {
            String text = null;
            boolean isFinal = false;
//            LogUtil.d(TAG, "response = " + response + "\nresults count = " + response.getResultsCount());
            if (response.getResultsCount() > 0) {
                final StreamingRecognitionResult result = response.getResults(0);
                isFinal = result.getIsFinal();
                if (result.getAlternativesCount() > 0) {
                    final SpeechRecognitionAlternative alternative = result.getAlternatives(0);
                    text = alternative.getTranscript();
//                    LogUtil.d(TAG, "callback text " + text);
                }
            }
            if (response.getError() != null) {
//                LogUtil.d(TAG, "response status is " + response.getError().getCode() + "  DetailsCount is " + response.getError().getDetailsCount());
                if (response.getError().getDetailsCount() > 0) {
//                    LogUtil.d(TAG, "response value is " + response.getError().getDetailsList().get(0).getValue().toString());
                }
//                LogUtil.d(TAG, "response errmsg is " + response.getError().getMessage());
                if (0 != response.getError().getCode() && 200 != response.getError().getCode()) {
                    if (mListener != null) {
//                        mListener.onEvent(SpeechConstants.ErrorDomain.ERR_ASR_ONLINE_SERVER, "error code:" + response.getError().getCode() + response.getError().getMessage().toString(), null, 0, 0);
                        mListener.onError(SpeechConstants.ErrorDomain.ERR_ASR_ONLINE_SERVER, response.getError().getCode(),response.getError().getMessage().toString(), null);
                    }
                }
            }
            if (text != null && mListener != null) {
                if (isFinal) {
                    mListener.onEvent(SpeechConstants.Message.MSG_ASR_ONLINE_LAST_RESULT, text, null, 0, 0);
                } else {
                    mListener.onEvent(SpeechConstants.Message.MSG_ASR_ONLINE_PART_RESULT, text, null, 0, 0);
                }
            }

        }

        @Override
        public void onError(Throwable t) {
            LogUtil.e(TAG, "Error calling the API." + t.getMessage());
            if (mListener != null) {
//                mListener.onEvent(SpeechConstants.ErrorDomain.ERR_ASR_ONLINE_NETWORK, "error code:-2 " + t.getMessage(), null, 0, 0);
                mListener.onError(SpeechConstants.ErrorDomain.ERR_ASR_ONLINE_NETWORK, ErrorIndex.ERROR_GRPC_SERVER, t.getMessage(),null);
            }
            t.printStackTrace();
        }

        @Override
        public void onCompleted() {
            LogUtil.d(TAG, "API completed.");
            if (mListener != null) {
                mListener.onEvent(SpeechConstants.Message.MSG_ASR_ONLINE_COMPLETED, "", null, 0, 0);
            } else {
                LogUtil.e(TAG, "err callback is null");
            }

        }
    };

    private void recognize(byte[] data, int size, long pkgID) {
        if(isCompleted){
            return;
        }
        if (mRequestObserver == null) {
            LogUtil.e(TAG,"mRequestObserver == null");
            return;
        }
        // Call the streaming recognition API
        ByteString tempData = null;
        tempData = ByteString.copyFrom(data);
        try {
            if (data != null && data.length > 0) {
                mRequestObserver.onNext(StreamingRecognizeRequest.newBuilder()
                        .setAudioContent(tempData)
                        .build());
                LogUtil.d(TAG , "recognize data(byte) length:" + data.length + " sn:" + pkgID);
            }

//            LogUtil.d(TAG,"packageReceivedID "+pkgID+" is dealed");
            if (pkgID < 0) {
                if(isCompleted){
//                    LogUtil.d(TAG, "recognize.onCompleted called already");
                    return;
                }
                LogUtil.d(TAG, "recognize.onCompleted");
                mRequestObserver.onCompleted();
                isCompleted = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (mListener != null) {
                mListener.onError(SpeechConstants.ErrorDomain.ERR_ASR_ONLINE_ENGINE_LOGIC, ErrorIndex.ERROR_NETWORK_UNAVAILABLE, e.getMessage(),null);
            }
            LogUtil.e(TAG, "Exception! :" + e.getMessage());
        }

    }

    private void finishRecognizing() {
        if (mRequestObserver == null) {
            return;
        }
        if (!isCompleted) {
            mRequestObserver.onCompleted();
        }
        mRequestObserver = null;
        if (client == null) {
            return;
        }
        final ManagedChannel channel = (ManagedChannel) client.getChannel();
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdownNow();
//                channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                LogUtil.e(TAG, "Error shutting down the gRPC channel. " + e.getMessage());
            }
        }


        LogUtil.d(TAG, "finishRecognizing");
        if(mListener != null){
            mListener.onEvent(SpeechConstants.Message.MSG_ASR_ONLINE_TERMINATION,"",null,0,0);
        }
    }

}
