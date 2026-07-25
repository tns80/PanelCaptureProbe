# Shizuku starts this class by name in a separate shell process.
-keep class org.boluo.panelprobe.shizuku.PanelControlUserService {
    public <init>();
    *;
}

-keep class org.boluo.panelprobe.shizuku.IPanelControl$Stub { *; }
-keep class org.boluo.panelprobe.shizuku.IPanelControl$Stub$Proxy { *; }
