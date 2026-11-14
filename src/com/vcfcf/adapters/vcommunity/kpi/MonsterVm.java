package com.vcfcf.adapters.vcommunity.kpi;

/** The size at which a VM becomes a monster on its host (the three account settings, map D2). */
public final class MonsterVm {
    public final double ratioOfCores;
    public final double ratioOfDram;
    public final double memoryMinMb;

    public MonsterVm(double ratioOfCores, double ratioOfDram, double memoryMinMb) {
        this.ratioOfCores = ratioOfCores;
        this.ratioOfDram = ratioOfDram;
        this.memoryMinMb = memoryMinMb;
    }

    /** From the whole-number settings, as the account holds them (percent, percent, gigabytes). */
    public static MonsterVm of(int cpuPercent, int memoryPercent, int memoryMinGb) {
        return new MonsterVm(cpuPercent / 100.0, memoryPercent / 100.0, memoryMinGb * 1024.0);
    }

    public static final MonsterVm DEFAULT = of(50, 40, 256);
}
