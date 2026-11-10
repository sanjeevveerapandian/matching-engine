package com.sanjeev.api;

import java.util.Random;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Demo traffic: {@code POST /api/simulate?count=1000}. */
@RestController
@RequestMapping("/api")
class SimulationController {

    private final TrafficSimulator simulator;

    SimulationController(TrafficSimulator simulator) {
        this.simulator = simulator;
    }

    /**
     * Places, cancels and modifies {@code count} orders through the exchange, then reports what
     * happened. {@code seed} makes a run repeatable; without it each run is different.
     */
    @PostMapping("/simulate")
    Responses.Simulation simulate(@RequestParam(name = "count", defaultValue = "1000") int count,
                                  @RequestParam(name = "seed", required = false) Long seed) {
        if (count < 1 || count > TrafficSimulator.MAX_COUNT) {
            throw new InvalidRequestException("count", "count must be between 1 and " + TrafficSimulator.MAX_COUNT);
        }
        long usedSeed = seed != null ? seed : new Random().nextLong();
        return Responses.Simulation.from(simulator.run(count, usedSeed), usedSeed);
    }
}
