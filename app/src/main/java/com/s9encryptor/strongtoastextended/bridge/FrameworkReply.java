package com.s9encryptor.strongtoastextended.bridge;

import android.os.Parcel;
import android.os.ResultReceiver;

/** Keeps app callback classes out of Intents delivered to a different process. */
public final class FrameworkReply {
    private FrameworkReply() { }

    public static ResultReceiver forTransport(ResultReceiver local) {
        Parcel parcel = Parcel.obtain();
        try {
            // writeParcelable would encode the anonymous app subclass name. Write only
            // ResultReceiver's Binder payload, then create an actual framework instance.
            // Its Binder still calls the original local receiver; no public reply channel.
            local.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }
}
