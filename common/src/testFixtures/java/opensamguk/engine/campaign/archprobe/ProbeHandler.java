package opensamguk.engine.campaign.archprobe;

import opensamguk.infra.archprobe.ProbeRepository;

public final class ProbeHandler {
    private final ProbeRepository repository;

    public ProbeHandler(ProbeRepository repository) {
        this.repository = repository;
    }
}
