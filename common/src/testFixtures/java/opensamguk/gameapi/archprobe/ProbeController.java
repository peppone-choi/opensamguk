package opensamguk.gameapi.archprobe;

import opensamguk.infra.archprobe.ProbeRepository;
import opensamguk.logic.archprobe.ProbeClock;

public final class ProbeController {
    private final ProbeRepository repository;
    private final ProbeClock logic;

    public ProbeController(ProbeRepository repository, ProbeClock logic) {
        this.repository = repository;
        this.logic = logic;
    }
}
