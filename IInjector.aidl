package app.soundpad;

import android.os.ParcelFileDescriptor;

interface IInjector {
    void destroy() = 16777114;
    String start(in ParcelFileDescriptor pcm, int rate, int excludeUid) = 2;
    void stop() = 3;
    String probe() = 4;
}
