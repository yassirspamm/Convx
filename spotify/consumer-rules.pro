-keep class com.music.spotify.nativecore.NativeBridge { *; }
-keep interface com.music.spotify.nativecore.NativeEvents { *; }
-keepclassmembers class * implements com.music.spotify.nativecore.NativeEvents {
    public void onEvent(java.lang.String, java.lang.String, long);
}
-keepclassmembers class * implements com.music.spotify.nativecore.NativeAudioOutput {
    public void start();
    public void stop();
    public void write(java.nio.ByteBuffer, int, int, int);
    public void writeVoice(java.nio.ByteBuffer, int, int, int);
}
