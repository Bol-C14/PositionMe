package com.openpositioning.PositionMe.data.remote;

import java.io.File;
import java.io.InputStream;

public interface ServerApi {
    void uploadFile(File file, ApiCallback<String> callback);
    void downloadZip(ApiCallback<InputStream> callback);
    void fetchInfo(ApiCallback<String> callback);
}
