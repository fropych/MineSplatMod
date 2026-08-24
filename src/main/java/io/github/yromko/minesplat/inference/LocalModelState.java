package io.github.yromko.minesplat.inference;

public enum LocalModelState {
    CHECKING,
    MISSING,
    DOWNLOADING,
    VERIFYING,
    CONVERTING,
    READY,
    FAILED
}
