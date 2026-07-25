package org.boluo.panelprobe.shizuku;

interface IPanelControl {
    void destroy() = 16777114;
    String probe() = 1;
    String startTimedPanelOff(int durationMillis) = 2;
    String forcePanelOn() = 3;
    String getCycleStatus() = 4;
}
