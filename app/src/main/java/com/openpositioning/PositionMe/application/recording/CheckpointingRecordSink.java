package com.openpositioning.PositionMe.application.recording;

/** A session sink that durably checkpoints the protobuf trajectory and records its outcome. */
public interface CheckpointingRecordSink extends RecordSink {
    void checkpoint(byte[] protobuf, long wallMillis, long elapsedMillis) throws Exception;
    String getSessionId();
    void complete() throws Exception;
    void fail(Throwable failure) throws Exception;
}
