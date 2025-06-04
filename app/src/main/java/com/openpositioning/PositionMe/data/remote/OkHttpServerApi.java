package com.openpositioning.PositionMe.data.remote;

import com.openpositioning.PositionMe.BuildConfig;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class OkHttpServerApi implements ServerApi {
    private static final String userKey = BuildConfig.OPENPOSITIONING_API_KEY;
    private static final String masterKey = BuildConfig.OPENPOSITIONING_MASTER_KEY;
    private static final String uploadURL =
            "https://openpositioning.org/api/live/trajectory/upload/" + userKey + "/?key=" + masterKey;
    private static final String downloadURL =
            "https://openpositioning.org/api/live/trajectory/download/" + userKey + "?skip=0&limit=30&key=" + masterKey;
    private static final String infoRequestURL =
            "https://openpositioning.org/api/live/users/trajectories/" + userKey + "?key=" + masterKey;
    private static final String PROTOCOL_CONTENT_TYPE = "multipart/form-data";
    private static final String PROTOCOL_ACCEPT_TYPE = "application/json";

    private final OkHttpClient client = new OkHttpClient();

    @Override
    public void uploadFile(File file, ApiCallback<String> callback) {
        RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(),
                        RequestBody.create(MediaType.parse("text/plain"), file))
                .build();
        Request request = new Request.Builder().url(uploadURL).post(requestBody)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .addHeader("Content-Type", PROTOCOL_CONTENT_TYPE).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful()) {
                        String err = body != null ? body.string() : "";
                        callback.onError(new IOException(err));
                        return;
                    }
                    callback.onSuccess(body != null ? body.string() : "");
                }
            }
        });
    }

    @Override
    public void downloadZip(ApiCallback<InputStream> callback) {
        Request request = new Request.Builder().url(downloadURL)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onError(new IOException("Unexpected code " + response));
                    return;
                }
                ResponseBody body = response.body();
                if (body != null) {
                    callback.onSuccess(body.byteStream());
                } else {
                    callback.onError(new IOException("Empty body"));
                }
            }
        });
    }

    @Override
    public void fetchInfo(ApiCallback<String> callback) {
        Request request = new Request.Builder().url(infoRequestURL)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful()) {
                        callback.onError(new IOException("Unexpected code " + response));
                        return;
                    }
                    callback.onSuccess(body != null ? body.string() : "");
                }
            }
        });
    }
}
