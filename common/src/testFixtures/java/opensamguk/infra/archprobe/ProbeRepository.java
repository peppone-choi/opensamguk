package opensamguk.infra.archprobe;

import opensamguk.gameapi.archprobe.ProbeController;

public final class ProbeRepository {
    private final ProbeController controller;

    public ProbeRepository(ProbeController controller) {
        this.controller = controller;
    }
}
