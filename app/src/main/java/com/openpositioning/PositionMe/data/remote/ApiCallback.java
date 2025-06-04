package com.openpositioning.PositionMe.data.remote;

public interface ApiCallback<T> {
    void onSuccess(T result);
    void onError(Exception e);
}
