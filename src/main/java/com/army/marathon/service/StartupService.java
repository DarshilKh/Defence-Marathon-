// src/main/java/com/army/marathon/service/StartupService.java
package com.army.marathon.service;

import com.army.marathon.model.Race;
import com.army.marathon.repository.RaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class StartupService implements CommandLineRunner {

    private final RaceRepository raceRepository;
    private final RaceLifecycleService raceLifecycleService;

    public StartupService(RaceRepository raceRepository, RaceLifecycleService raceLifecycleService) {
        this.raceRepository = raceRepository;
        this.raceLifecycleService = raceLifecycleService;
    }

    @Override
    public void run(String... args) {
        // Before anything else: close any attempt left open by a crash, kill or power loss (the
        // close dialog can't show for those). Order matters - resetToFreshState() forgets which
        // session was current, so orphans must be closed while that is still known.
        int closed = raceLifecycleService.closeOrphanSessions();
        if (closed > 0) {
            log.warn("Closed {} orphaned race session(s) left open by an unclean shutdown", closed);
        }

        if (raceRepository.count() == 0) {
            Race defaultRace = new Race();
            defaultRace.setRaceName("Indian Defence Marathon 2025");
            defaultRace.setRaceDate(LocalDateTime.now());
            defaultRace.setStatus(Race.RaceStatus.NOT_STARTED);
            defaultRace.setDescription("Annual Defence Marathon Event");
            raceRepository.save(defaultRace);
            log.info("✅ Default race created");
        }

        // Every launch comes up as if no race exists (display state only - history stays).
        raceLifecycleService.resetToFreshState();
    }
}
