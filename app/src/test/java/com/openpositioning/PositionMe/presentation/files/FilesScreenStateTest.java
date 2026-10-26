package com.openpositioning.PositionMe.presentation.files;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class FilesScreenStateTest {
    @Test public void remoteCatalogIsNotLoadedUntilRequested() {
        assertEquals(FilesScreenState.RemoteStatus.NOT_LOADED,
                FilesScreenState.initial().getRemoteStatus());
    }

    @Test public void loadingCanFinishAsLoadedOrEmpty() {
        FilesScreenState loading = FilesScreenState.initial().beginRemoteLoad();
        assertEquals(FilesScreenState.RemoteStatus.LOADED,
                loading.finishRemoteLoad(loading.getRequestGeneration(), true).getRemoteStatus());
        assertEquals(FilesScreenState.RemoteStatus.EMPTY,
                loading.finishRemoteLoad(loading.getRequestGeneration(), false).getRemoteStatus());
    }

    @Test public void malformedOrFailedLoadHasRetryableFailureState() {
        FilesScreenState loading = FilesScreenState.initial().beginRemoteLoad();
        FilesScreenState failed = loading.failRemoteLoad(loading.getRequestGeneration());
        assertEquals(FilesScreenState.RemoteStatus.FAILED, failed.getRemoteStatus());
        FilesScreenState retry = failed.beginRemoteLoad();
        assertEquals(FilesScreenState.RemoteStatus.LOADING, retry.getRemoteStatus());
        assertEquals(failed.getRequestGeneration() + 1, retry.getRequestGeneration());
    }

    @Test public void staleCompletionCannotOverwriteNewerRequest() {
        FilesScreenState first = FilesScreenState.initial().beginRemoteLoad();
        FilesScreenState second = first.beginRemoteLoad();
        assertSame(second, second.finishRemoteLoad(first.getRequestGeneration(), true));
        assertEquals(FilesScreenState.RemoteStatus.LOADING, second.getRemoteStatus());
        assertEquals(FilesScreenState.RemoteStatus.LOADED,
                second.finishRemoteLoad(second.getRequestGeneration(), true).getRemoteStatus());
    }
}
