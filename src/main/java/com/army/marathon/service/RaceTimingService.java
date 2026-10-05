package com.army.marathon.service;

import com.army.marathon.model.*;
import com.army.marathon.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RaceTimingService {

    private final ParticipantRepository participantRepository;
    private final CheckpointReadingRepository readingRepository;
    private final RaceResultRepository resultRepository;
    private final CheckpointRepository checkpointRepository;
    private final RaceRepository raceRepository; // ✅✅✅ ADDED
    private final RaceEntryRepository raceEntryRepository;

    public RaceTimingService(
        ParticipantRepository participantRepository,
        CheckpointReadingRepository readingRepository,
        RaceResultRepository resultRepository,
        CheckpointRepository checkpointRepository,
        RaceRepository raceRepository, // ✅✅✅ ADDED
        RaceEntryRepository raceEntryRepository
    ) {
        this.participantRepository = participantRepository;
        this.readingRepository = readingRepository;
        this.resultRepository = resultRepository;
        this.checkpointRepository = checkpointRepository;
        this.raceRepository = raceRepository; // ✅✅✅ ADDED
        this.raceEntryRepository = raceEntryRepository;
    }

    /**
     * The RaceSession (attempt) a freshly-calculated RaceResult should be tagged with.
     *
     * This is the fix for the Reports tab showing "0 participants" / "No results
     * calculated" for real historical attempts: RaceResult was previously only
     * scoped by createdAt (whenever Calculate Results happened to be clicked), which
     * has no reliable relationship to when the race itself ran, since Calculate
     * Results is a separate manual action that can be clicked long after Stop Race
     * ended the session. Tagging each result with the race's CURRENT session id at
     * calculation time gives the Reports tab a definitive link instead of an
     * unreliable timestamp-window guess. Throws for a race with no current attempt (never
     * started, or freshly reset) rather than stamping a null session id: a null-session result
     * belongs to no Reports attempt and could never be found or deleted from the UI.
     */
    private Long resolveSessionId(Long raceId) {
        return raceRepository.findById(raceId)
                .map(Race::getCurrentSessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "No current race attempt - cannot record results without a session"));
    }

    /**
     * ✅ Who gets a result row: the race's roster if one was saved (so roster members
     * with zero readings still appear), otherwise every registered participant, as
     * before rosters existed.
     */
    private List<Participant> participantsForResults(Long raceId, boolean rosterActive) {
        if (!rosterActive) {
            return participantRepository.findAll();
        }

        List<Long> rosterIds = raceEntryRepository.findByRaceId(raceId).stream()
            .map(RaceEntry::getParticipantId)
            .collect(Collectors.toList());
        List<Participant> roster = participantRepository.findAllById(rosterIds);

        // Off-roster scans are rejected live, but readings recorded before the roster
        // was saved are still in the table - they're excluded from results, so say so.
        Set<String> rosterTags = roster.stream()
            .map(Participant::getRfidTag)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        long excluded = readingRepository.findDistinctRfidTagsByRaceId(raceId).stream()
            .filter(tag -> !rosterTags.contains(tag))
            .count();
        if (excluded > 0) {
            log.warn("⚠️ {} tag(s) have readings in race {} but are not on its roster - excluded from results",
                excluded, raceId);
        }

        log.info("📋 Roster active for race {}: building results for {} roster member(s)", raceId, roster.size());
        return roster;
    }

    /**
     * ✅ A roster member with no readings at all: explicit DNF / No-Show (checkpointCount 0).
     */
    private RaceResult noShowResult(Participant participant, Long raceId) {
        Long sessionId = resolveSessionId(raceId);
        RaceResult result = new RaceResult();
        result.setRaceSessionId(sessionId);
        result.setParticipant(participant);
        result.setRaceId(raceId);
        result.setRfidTag(participant.getRfidTag());
        result.setCheckpointCount(0);
        result.setCompletedRace(false);
        result.setFormattedTime("DNF");
        log.warn("⚠️ {} - No-Show (on roster, no readings)", participant.getName());
        return result;
    }

    /** Saves a No-Show row if the race has a roster. Returns true if a row was saved. */
    private boolean saveNoShowIfRostered(boolean rosterActive, Participant participant, Long raceId) {
        if (!rosterActive) {
            return false;
        }
        resultRepository.save(noShowResult(participant, raceId));
        return true;
    }

    /**
     * Calculate race results with specified mode ⭐
     * ✅✅✅ UPDATED: Now handles single file mode
     */
    @Transactional
    public void calculateRaceResults(Long raceId, Race.CalculationMode mode) {
        log.info("🧮 Calculating race results for race {} with mode: {}", raceId, mode);

        Race race = raceRepository.findById(raceId)
            .orElseThrow(() -> new RuntimeException("Race not found"));
        // Only readings from the CURRENT attempt (since the last fresh Start Race) feed a
        // (re)calculation, so re-arming and re-running the same race after a Reset never mixes
        // an earlier attempt's readings into this one. No current attempt (never started, or
        // freshly reset) means there is nothing to calculate - it must never mean "every reading
        // on file", which would merge all history into one result set.
        if (!race.hasCurrentAttempt()) {
            throw new IllegalStateException("No current race attempt - start a race before calculating results");
        }
        LocalDateTime attemptStart = race.getCurrentAttemptStartedAt();

        // Clear this SAME attempt's previously-calculated results first, so clicking
        // "Calculate Results" a second time for the same live attempt replaces its
        // rows instead of duplicating every participant alongside the old ones. Scoped
        // by session id, not raceId alone, so no other attempt's results are touched.
        resultRepository.deleteByRaceSessionId(race.getCurrentSessionId());

        // ✅✅✅ NEW: Handle single file mode differently
        if (mode == Race.CalculationMode.SINGLE_FILE_MANUAL_START) {
            if (race.getManualStartTime() == null) {
                throw new RuntimeException("Manual start time not set for single file mode");
            }

            calculateSingleFileResults(raceId, race.getManualStartTime());
            return; // ✅ Exit here for single file mode
        }

        if (mode == Race.CalculationMode.SAME_START_FINISH) {
            calculateSameStartFinishResults(raceId, attemptStart);
            return;
        }

        if (mode == Race.CalculationMode.MANUAL_START_DUAL_TIMING) {
            if (race.getManualStartTime() == null) {
                throw new RuntimeException("Declared Start not set - press \"Set\" on the Live Tracking page to declare the race start before calculating");
            }
            calculateManualStartDualTimingResults(raceId, race.getManualStartTime(), attemptStart);
            return;
        }

        if (mode == Race.CalculationMode.LAP_RACE) {
            if (race.getLapCount() == null || race.getLapCount() <= 0) {
                throw new RuntimeException("Lap count not configured for lap race mode");
            }
            calculateLapRaceResults(raceId, race.getLapCount(), attemptStart);
            return;
        }

        if (mode == Race.CalculationMode.LONG_DISTANCE_MANUAL_START) {
            // ✅ The start is ALWAYS the auto-detected one (first valid scan at the start-line
            // checkpoint, see MultiReaderService.maybeAutoStartRace). manualStartTime belongs
            // to MANUAL_START_DUAL_TIMING and is deliberately ignored here - no manual override.
            if (race.getStartTime() == null) {
                throw new RuntimeException("Start time not detected for long distance mode - tick Start on a checkpoint, start the race, and let the first tag scan at that start line set it");
            }
            calculateLongDistanceResults(raceId, race.getStartTime(), attemptStart);
            return;
        }

        if (mode == Race.CalculationMode.LONG_DISTANCE_2_MANUAL_START) {
            if (race.getStartTime() == null) {
                throw new RuntimeException("Start time not detected for long distance 2 mode - tick Start on a checkpoint, start the race, and let the first tag scan at that start line set it");
            }
            calculateLongDistance2Results(raceId, race.getStartTime(), attemptStart);
            return;
        }

        // ✅ NORMAL MODE: Get required checkpoints for THIS race
        List<Checkpoint> requiredCheckpoints = getRequiredCheckpoints(raceId, mode);

        if (requiredCheckpoints.isEmpty()) {
            throw new RuntimeException("No checkpoints configured for calculation");
        }

        Checkpoint startLine = requiredCheckpoints.get(0);
        Checkpoint finishLine = requiredCheckpoints.get(requiredCheckpoints.size() - 1);

        log.info("📍 Using {} checkpoints: {} to {}",
            requiredCheckpoints.size(), startLine.getName(), finishLine.getName());

        boolean rosterActive = raceEntryRepository.existsByRaceId(raceId);
        List<Participant> participants = participantsForResults(raceId, rosterActive);

        int totalProcessed = 0;
        int completed = 0;
        int dnf = 0;

        for (Participant participant : participants) {
            try {
                RaceResult result = calculateParticipantResult(
                    participant, raceId, startLine, finishLine,
                    requiredCheckpoints, mode, rosterActive, attemptStart
                );

                if (result != null) {
                    resultRepository.save(result);
                    totalProcessed++;

                    if (result.isCompletedRace()) {
                        completed++;
                    } else {
                        dnf++;
                    }
                }

            } catch (Exception e) {
                log.error("Error calculating result for {}: {}",
                    participant.getName(), e.getMessage());
            }
        }

        log.info("✅ Race results calculation completed: {} processed, {} completed, {} DNF",
            totalProcessed, completed, dnf);
    }

    /**
     * Readings for this tag/race from the current attempt onward - see {@link #calculateRaceResults}.
     * A null {@code attemptStart} (no current attempt) yields nothing, never every reading on file.
     */
    private List<CheckpointReading> readingsSinceAttemptStart(String rfidTag, Long raceId, LocalDateTime attemptStart) {
        if (attemptStart == null) {
            return new ArrayList<>();
        }
        List<CheckpointReading> all = readingRepository.findByRfidTagAndRaceIdOrderByReadTimeAsc(rfidTag, raceId);
        return all.stream()
            .filter(r -> r.getReadTime() != null && !r.getReadTime().isBefore(attemptStart))
            .collect(Collectors.toList());
    }

    /**
     * Enabled checkpoints that apply to this race, in display order.
     *
     * Checkpoints created on the Configuration page carry no race id (the page configures
     * the physical locations, not one race), so a strict race-id match found none of them.
     * Checkpoints tied to this race win if there are any; otherwise the unassigned ones apply.
     */
    public List<Checkpoint> enabledCheckpointsFor(Long raceId) {
        List<Checkpoint> forRace = checkpointRepository.findByRaceIdAndEnabledTrueOrderByDisplayOrderAsc(raceId);
        if (!forRace.isEmpty()) {
            return forRace;
        }
        return checkpointRepository.findByEnabledTrueOrderByDisplayOrderAsc().stream()
            .filter(cp -> cp.getRaceId() == null)
            .collect(Collectors.toList());
    }

    /**
     * ✅ FIXED: Get required checkpoints for specific race
     */
    private List<Checkpoint> getRequiredCheckpoints(Long raceId, Race.CalculationMode mode) {
        // ✅ CRITICAL FIX: Filter by raceId to get correct checkpoints
        List<Checkpoint> allCheckpoints = enabledCheckpointsFor(raceId);
        
        if (allCheckpoints.isEmpty()) {
            log.error("❌ No enabled checkpoints found for race {}!", raceId);
            return Collections.emptyList();
        }
        
        log.info("📍 Found {} total checkpoints for race {}", allCheckpoints.size(), raceId);
        
        // Find start and finish
        Checkpoint start = allCheckpoints.stream()
            .filter(Checkpoint::isStartLine)
            .findFirst()
            .orElse(allCheckpoints.get(0));
        
        Checkpoint finish = allCheckpoints.stream()
            .filter(Checkpoint::isFinishLine)
            .findFirst()
            .orElse(allCheckpoints.get(allCheckpoints.size() - 1));
        
        switch (mode) {
            case TWO_CHECKPOINT:
                // Start + Finish only
                log.info("📍 Mode: 2 checkpoints - {} and {}", start.getName(), finish.getName());
                return Arrays.asList(start, finish);
                
            case THREE_CHECKPOINT:
                // Start + 1 Middle + Finish
                if (allCheckpoints.size() >= 3) {
                    log.info("📍 Mode: 3 checkpoints");
                    return Arrays.asList(
                        allCheckpoints.get(0),
                        allCheckpoints.get(1),
                        allCheckpoints.get(allCheckpoints.size() - 1)
                    );
                }
                break;
                
            case FOUR_CHECKPOINT:
                // Start + 2 Middle + Finish
                if (allCheckpoints.size() >= 4) {
                    log.info("📍 Mode: 4 checkpoints");
                    return Arrays.asList(
                        allCheckpoints.get(0),
                        allCheckpoints.get(1),
                        allCheckpoints.get(2),
                        allCheckpoints.get(allCheckpoints.size() - 1)
                    );
                }
                break;
                
            case FIVE_CHECKPOINT:
                // Start + 3 Middle + Finish
                if (allCheckpoints.size() >= 5) {
                    log.info("📍 Mode: 5 checkpoints");
                    return allCheckpoints.subList(0, 5);
                }
                break;
                
            case AUTO:
            default:
                // Use all enabled checkpoints
                log.info("📍 Mode: AUTO - using all {} checkpoints", allCheckpoints.size());
                return allCheckpoints;
        }
        
        // Fallback: return all available checkpoints
        log.warn("⚠️ Requested {} but only {} checkpoints available, using all", 
            mode, allCheckpoints.size());
        return allCheckpoints;
    }

    /**
     * Calculate individual participant result ⭐
     */
    private RaceResult calculateParticipantResult(
        Participant participant,
        Long raceId,
        Checkpoint startLine,
        Checkpoint finishLine,
        List<Checkpoint> requiredCheckpoints,
        Race.CalculationMode mode,
        boolean rosterActive,
        LocalDateTime attemptStart
    ) {
        Long sessionId = resolveSessionId(raceId);
        String rfidTag = participant.getRfidTag();

        if (rfidTag == null || rfidTag.trim().isEmpty()) {
            log.warn("⚠️ Participant {} has no RFID tag", participant.getName());
            return rosterActive ? noShowResult(participant, raceId) : null;
        }

        // Get all readings for this participant, scoped to the current attempt
        List<CheckpointReading> allReadings = readingsSinceAttemptStart(rfidTag, raceId, attemptStart);
        
        if (allReadings.isEmpty()) {
            log.debug("No readings found for participant: {}", participant.getName());
            return rosterActive ? noShowResult(participant, raceId) : null;
        }

        log.debug("📊 {} has {} readings", participant.getName(), allReadings.size());

        // ✅ Check if participant passed all required checkpoints
        boolean passedAllCheckpoints = checkAllCheckpointsPassed(
            allReadings, requiredCheckpoints
        );
        
        // Get start and finish times
        Optional<CheckpointReading> startReading = findReadingAtCheckpoint(
            allReadings, startLine
        );
        
        Optional<CheckpointReading> finishReading = findReadingAtCheckpoint(
            allReadings, finishLine
        );
        
        log.debug("📊 {}: passedAll={}, hasStart={}, hasFinish={}", 
            participant.getName(), passedAllCheckpoints, 
            startReading.isPresent(), finishReading.isPresent());
        
        // Create result (completed or DNF)
        RaceResult result = new RaceResult();
        result.setRaceSessionId(sessionId);
        result.setParticipant(participant);
        result.setRaceId(raceId);
        result.setRfidTag(rfidTag);
        result.setCheckpointCount(allReadings.size());
        
        if (passedAllCheckpoints && startReading.isPresent() && finishReading.isPresent()) {
            // COMPLETED RACE ✅
            LocalDateTime startTime = startReading.get().getReadTime();
            LocalDateTime finishTime = finishReading.get().getReadTime();
            Duration netTime = Duration.between(startTime, finishTime);
            
            result.setStartTime(startTime);
            result.setFinishTime(finishTime);
            result.setNetTimeSeconds(netTime.getSeconds());
            result.setFormattedTime(formatDuration(netTime));
            result.setCompletedRace(true);
            
            // Calculate split times
            Map<String, String> splits = calculateSplits(allReadings, startTime);
            result.setSplitTimesJson(splits.toString());
            
            log.info("✅ {} completed in {}", participant.getName(), result.getFormattedTime());
            
        } else {
            // DID NOT FINISH (DNF) ⚠️
            result.setCompletedRace(false);
            result.setFormattedTime("DNF");
            
            // Record partial completion
            if (startReading.isPresent()) {
                result.setStartTime(startReading.get().getReadTime());
                
                // Calculate split times for checkpoints they did pass
                Map<String, String> splits = calculateSplits(
                    allReadings, startReading.get().getReadTime()
                );
                result.setSplitTimesJson(splits.toString());
            }
            
            log.warn("⚠️ {} - DNF (passed {}/{} required checkpoints)", 
                participant.getName(), allReadings.size(), requiredCheckpoints.size());
        }
        
        return result;
    }

    /**
     * ✅ FIXED: Check if participant passed all required checkpoints
     */
    private boolean checkAllCheckpointsPassed(
        List<CheckpointReading> readings,
        List<Checkpoint> requiredCheckpoints
    ) {
        // Get set of checkpoint names the participant passed
        Set<String> passedCheckpointNames = readings.stream()
            .filter(r -> r.getCheckpointName() != null)
            .map(CheckpointReading::getCheckpointName)
            .collect(Collectors.toSet());
        
        log.debug("📊 Passed checkpoints: {}", passedCheckpointNames);
        
        // ✅ CRITICAL FIX: Use getName() instead of getLocationName()
        for (Checkpoint required : requiredCheckpoints) {
            String requiredName = required.getName();
            if (!passedCheckpointNames.contains(requiredName)) {
                log.warn("⚠️ Missing checkpoint: {} (have: {})", requiredName, passedCheckpointNames);
                return false;
            }
        }
        
        log.debug("✅ All {} required checkpoints passed", requiredCheckpoints.size());
        return true;
    }

    /**
     * ✅ FIXED: Find reading at specific checkpoint
     */
    private Optional<CheckpointReading> findReadingAtCheckpoint(
        List<CheckpointReading> readings,
        Checkpoint checkpoint
    ) {
        // ✅ CRITICAL FIX: Use getName() for comparison
        return readings.stream()
            .filter(r -> r.getCheckpointName() != null && 
                        r.getCheckpointName().equals(checkpoint.getName()))
            .findFirst();
    }

    /**
     * Calculate split times for all checkpoints ⭐
     */
    private Map<String, String> calculateSplits(
        List<CheckpointReading> readings, 
        LocalDateTime startTime
    ) {
        Map<String, String> splits = new LinkedHashMap<>();
        
        for (CheckpointReading reading : readings) {
            Duration split = Duration.between(startTime, reading.getReadTime());
            splits.put(
                reading.getCheckpointName(), 
                formatDuration(split)
            );
        }
        
        return splits;
    }

    /**
     * Format duration as HH:MM:SS
     */
    String formatDuration(Duration duration) {
        // A negative span (e.g. finish before a mis-declared start) keeps its sign visible
        // instead of rendering per-field negatives like "-1:-30:-05".
        String sign = duration.isNegative() ? "-" : "";
        Duration abs = duration.abs();
        return String.format("%s%02d:%02d:%02d", sign, abs.toHours(), abs.toMinutesPart(), abs.toSecondsPart());
    }

    /**
     * ✅✅✅ NEW: Calculate race results for SINGLE FILE mode (manual start time)
     */
    /**
 * ✅✅✅ FIXED: Calculate race results for SINGLE FILE mode (manual start time)
 * Only process participants who have readings in the uploaded file
 */
@Transactional
    public void calculateSingleFileResults(Long raceId, LocalDateTime manualStartTime) {
        log.info("🧮 Calculating SINGLE FILE results for race {} with manual start: {}", 
            raceId, manualStartTime);

    // Get the single finish line checkpoint
    List<Checkpoint> checkpoints = checkpointRepository
        .findByRaceIdAndEnabledTrueOrderByDisplayOrderAsc(raceId);
    
    if (checkpoints.isEmpty() || !checkpoints.get(0).isFinishLine()) {
        throw new RuntimeException("No finish line checkpoint found for single file mode");
    }
    
    Checkpoint finishLine = checkpoints.get(0);
    log.info("📍 Using finish checkpoint: {}", finishLine.getName());

    // ✅✅✅ CRITICAL FIX: Get ONLY participants who have readings (from uploaded file)
    List<CheckpointReading> finishReadings = readingRepository
        .findByRaceIdOrderByReadTimeAsc(raceId)
        .stream()
        .filter(r -> r.getParticipant() != null)
        .filter(r -> r.isUploadedToServer()) // ✅ Only imported from Excel
        .collect(Collectors.toList());
    
    log.info("📊 Found {} participants with finish line readings", finishReadings.size());
    
    if (finishReadings.isEmpty()) {
        log.warn("⚠️ No finish line readings found!");
        return;
    }
    
    int totalProcessed = 0;
    int completed = 0;

    // ✅ Process ONLY participants who have readings
    for (CheckpointReading reading : finishReadings) {
        try {
            Participant participant = reading.getParticipant();
            
            RaceResult result = calculateSingleFileParticipantResult(
                participant, raceId, manualStartTime, finishLine, reading
            );
            
            if (result != null) {
                resultRepository.save(result);
                totalProcessed++;
                completed++;
            }
            
        } catch (Exception e) {
            log.error("Error calculating result: {}", e.getMessage());
        }
    }
    
    log.info("✅ Single file calculation completed: {} processed, {} completed (No DNF)", 
        totalProcessed, completed);
}

/**
 * ✅✅✅ UPDATED: Calculate individual participant result for single file mode
 */
private RaceResult calculateSingleFileParticipantResult(
    Participant participant,
    Long raceId,
    LocalDateTime manualStartTime,
    Checkpoint finishLine,
    CheckpointReading finishReading // ✅ Pass reading directly
) {
    Long sessionId = resolveSessionId(raceId);
    String rfidTag = participant.getRfidTag();
    
    if (rfidTag == null || rfidTag.trim().isEmpty()) {
        log.warn("⚠️ Participant {} has no RFID tag", participant.getName());
        return null;
    }

    LocalDateTime finishTime = finishReading.getReadTime();
    
    // Calculate net time: finish time - manual start time
    Duration netTime = Duration.between(manualStartTime, finishTime);
    
    // Create completed result
    RaceResult result = new RaceResult();
    result.setRaceSessionId(sessionId);
    result.setParticipant(participant);
    result.setRaceId(raceId);
    result.setRfidTag(rfidTag);
    result.setStartTime(manualStartTime); // Manual start time
    result.setFinishTime(finishTime);
    result.setNetTimeSeconds(netTime.getSeconds());
    result.setFormattedTime(formatDuration(netTime));
    result.setCompletedRace(true); // ✅ All are finished (no DNF in single file mode)
    result.setCheckpointCount(1); // Only finish line
    
    log.info("✅ {} completed in {} (Start: {}, Finish: {})", 
        participant.getName(), 
        result.getFormattedTime(),
        manualStartTime.toLocalTime(),
        finishTime.toLocalTime());
    
    return result;
}

    /**
     * Calculate results when start and finish are the same checkpoint.
     * First read is start, second read is finish.
     */
    @Transactional
    public void calculateSameStartFinishResults(Long raceId, LocalDateTime attemptStart) {
        Long sessionId = resolveSessionId(raceId);
        log.info("🧮 Calculating SAME START/FINISH results for race {}", raceId);

        boolean rosterActive = raceEntryRepository.existsByRaceId(raceId);
        List<Participant> participants = participantsForResults(raceId, rosterActive);
        int processed = 0;
        int completed = 0;

        for (Participant participant : participants) {
            try {
                String rfidTag = participant.getRfidTag();
                if (rfidTag == null || rfidTag.trim().isEmpty()) {
                    if (saveNoShowIfRostered(rosterActive, participant, raceId)) processed++;
                    continue;
                }

                List<CheckpointReading> readings = readingsSinceAttemptStart(rfidTag, raceId, attemptStart);

                if (readings.isEmpty()) {
                    if (saveNoShowIfRostered(rosterActive, participant, raceId)) processed++;
                    continue;
                }

                RaceResult result = new RaceResult();
                result.setRaceSessionId(sessionId);
                result.setParticipant(participant);
                result.setRaceId(raceId);
                result.setRfidTag(rfidTag);
                result.setCheckpointCount(readings.size());

                if (readings.size() >= 2) {
                    LocalDateTime startTime = readings.get(0).getReadTime();
                    LocalDateTime finishTime = readings.get(1).getReadTime();
                    Duration netTime = Duration.between(startTime, finishTime);

                    result.setStartTime(startTime);
                    result.setFinishTime(finishTime);
                    result.setNetTimeSeconds(netTime.getSeconds());
                    result.setFormattedTime(formatDuration(netTime));
                    result.setCompletedRace(true);
                    completed++;
                } else {
                    result.setStartTime(readings.get(0).getReadTime());
                    result.setCompletedRace(false);
                    result.setFormattedTime("DNF");
                }

                resultRepository.save(result);
                processed++;
            } catch (Exception e) {
                log.error("Error calculating same start/finish result: {}", e.getMessage());
            }
        }

        log.info("✅ Same start/finish calculation completed: {} processed, {} completed", processed, completed);
    }

    /**
     * Calculate results for lap race mode.
     *
     * <p>An N-lap race needs N+1 total checkpoint crossings, not N: crossing #1 is the start
     * (elapsed time zero, not a completed lap), crossing #2 completes Lap 1, crossing #3
     * completes Lap 2, ..., crossing #(N+1) completes Lap N and is also the finish. This used to
     * require only N readings (finish = readings.get(lapCount - 1)), silently completing a
     * 3-lap race off 3 scans instead of 4 and mislabeling the very first scan - the start - as
     * "Lap 1". Confirmed from a real result whose Total Time exactly equalled Lap3Time minus
     * Lap1Time.
     */
    @Transactional
    public void calculateLapRaceResults(Long raceId, int lapCount, LocalDateTime attemptStart) {
        Long sessionId = resolveSessionId(raceId);
        log.info("🧮 Calculating LAP RACE results for race {} with {} laps", raceId, lapCount);

        boolean rosterActive = raceEntryRepository.existsByRaceId(raceId);
        List<Participant> participants = participantsForResults(raceId, rosterActive);
        int processed = 0;
        int completed = 0;

        // Crossing #1 (start) + one crossing per lap.
        int requiredReadings = lapCount + 1;

        for (Participant participant : participants) {
            try {
                String rfidTag = participant.getRfidTag();
                if (rfidTag == null || rfidTag.trim().isEmpty()) {
                    if (saveNoShowIfRostered(rosterActive, participant, raceId)) processed++;
                    continue;
                }

                List<CheckpointReading> readings = readingsSinceAttemptStart(rfidTag, raceId, attemptStart);

                if (readings.isEmpty()) {
                    if (saveNoShowIfRostered(rosterActive, participant, raceId)) processed++;
                    continue;
                }

                RaceResult result = new RaceResult();
                result.setRaceSessionId(sessionId);
                result.setParticipant(participant);
                result.setRaceId(raceId);
                result.setRfidTag(rfidTag);
                result.setCheckpointCount(readings.size());

                LocalDateTime startTime = readings.get(0).getReadTime();
                result.setStartTime(startTime);

                if (readings.size() >= requiredReadings) {
                    // The (lapCount + 1)-th reading: crossing #(N+1), which completes the final
                    // lap and is also the finish.
                    LocalDateTime finishTime = readings.get(lapCount).getReadTime();
                    Duration netTime = Duration.between(startTime, finishTime);

                    result.setFinishTime(finishTime);
                    result.setNetTimeSeconds(netTime.getSeconds());
                    result.setFormattedTime(formatDuration(netTime));
                    result.setCompletedRace(true);

                    // Each lap's own split - crossing N minus crossing N-1 - not elapsed-since-start,
                    // so these sum exactly to Total Time (a telescoping sum) and each one means
                    // "how long did this lap take" on its own, matching how the Results/Reports
                    // tabs and the Excel export now show these.
                    Map<String, String> splits = new LinkedHashMap<>();
                    for (int lap = 1; lap <= lapCount; lap++) {
                        LocalDateTime lapStart = readings.get(lap - 1).getReadTime();
                        LocalDateTime lapEnd = readings.get(lap).getReadTime();
                        splits.put("Lap " + lap, formatDuration(Duration.between(lapStart, lapEnd)));
                    }
                    result.setSplitTimesJson(splits.toString());
                    completed++;
                } else {
                    result.setCompletedRace(false);
                    result.setFormattedTime("DNF");
                }

                resultRepository.save(result);
                processed++;
            } catch (Exception e) {
                log.error("Error calculating lap result: {}", e.getMessage());
            }
        }

        log.info("✅ Lap race calculation completed: {} processed, {} completed", processed, completed);
    }

    /**
     * Calculate results for long distance mode using manual start time + midpoint + finish.
     */
    @Transactional
    public void calculateLongDistanceResults(Long raceId, LocalDateTime manualStartTime, LocalDateTime attemptStart) {
        Long sessionId = resolveSessionId(raceId);
        log.info("🧮 Calculating LONG DISTANCE results for race {} with manual start {}", raceId, manualStartTime);

        List<Checkpoint> checkpoints = enabledCheckpointsFor(raceId);
        if (checkpoints.isEmpty()) {
            throw new RuntimeException("No checkpoints configured for long distance mode");
        }

        Checkpoint finishLine = checkpoints.stream()
            .filter(Checkpoint::isFinishLine)
            .findFirst()
            .orElse(checkpoints.get(checkpoints.size() - 1));

        // ✅ Excludes any checkpoint flagged as the start line - that one only
        // exists to trigger auto-start detection (first scan = official start),
        // it's never a split/midpoint reading.
        Checkpoint midPoint = checkpoints.stream()
            .filter(cp -> !cp.isFinishLine() && !cp.isStartLine())
            .findFirst()
            .orElse(checkpoints.get(0));

        boolean rosterActive = raceEntryRepository.existsByRaceId(raceId);
        List<Participant> participants = participantsForResults(raceId, rosterActive);
        int processed = 0;
        int completed = 0;

        for (Participant participant : participants) {
            try {
                String rfidTag = participant.getRfidTag();
                if (rfidTag == null || rfidTag.trim().isEmpty()) {
                    if (saveNoShowIfRostered(rosterActive, participant, raceId)) processed++;
                    continue;
                }

                List<CheckpointReading> readings = readingsSinceAttemptStart(rfidTag, raceId, attemptStart);

                if (readings.isEmpty()) {
                    if (saveNoShowIfRostered(rosterActive, participant, raceId)) processed++;
                    continue;
                }

                Optional<CheckpointReading> midReading = findReadingAtCheckpoint(readings, midPoint);
                Optional<CheckpointReading> finishReading = findReadingAtCheckpoint(readings, finishLine);

                RaceResult result = new RaceResult();
                result.setRaceSessionId(sessionId);
                result.setParticipant(participant);
                result.setRaceId(raceId);
                result.setRfidTag(rfidTag);
                result.setStartTime(manualStartTime);
                result.setCheckpointCount(readings.size());

                if (midReading.isPresent() && finishReading.isPresent()) {
                    LocalDateTime finishTime = finishReading.get().getReadTime();
                    Duration netTime = Duration.between(manualStartTime, finishTime);

                    result.setFinishTime(finishTime);
                    result.setNetTimeSeconds(netTime.getSeconds());
                    result.setFormattedTime(formatDuration(netTime));
                    result.setCompletedRace(true);

                    Map<String, String> splits = new LinkedHashMap<>();
                    splits.put("Midpoint", formatDuration(Duration.between(manualStartTime, midReading.get().getReadTime())));
                    splits.put("Finish", formatDuration(netTime));
                    result.setSplitTimesJson(splits.toString());
                    completed++;
                } else {
                    result.setCompletedRace(false);
                    result.setFormattedTime("DNF");
                }

                resultRepository.save(result);
                processed++;
            } catch (Exception e) {
                log.error("Error calculating long distance result: {}", e.getMessage());
            }
        }

        log.info("✅ Long distance calculation completed: {} processed, {} completed", processed, completed);
    }

    /**
     * Calculate results for "Long Distance 2" mode: start (manual or
     * auto-detected) + 2 midpoints + finish.
     */
    @Transactional
    public void calculateLongDistance2Results(Long raceId, LocalDateTime startTime, LocalDateTime attemptStart) {
        Long sessionId = resolveSessionId(raceId);
        log.info("🧮 Calculating LONG DISTANCE 2 results for race {} with start {}", raceId, startTime);

        List<Checkpoint> checkpoints = enabledCheckpointsFor(raceId);
        if (checkpoints.isEmpty()) {
            throw new RuntimeException("No checkpoints configured for long distance 2 mode");
        }

        Checkpoint finishLine = checkpoints.stream()
            .filter(Checkpoint::isFinishLine)
            .findFirst()
            .orElse(checkpoints.get(checkpoints.size() - 1));

        // ✅ Excludes the start-line checkpoint (if any) - it only exists to
        // trigger auto-start detection, never a split/midpoint reading.
        List<Checkpoint> midPoints = checkpoints.stream()
            .filter(cp -> !cp.isFinishLine() && !cp.isStartLine())
            .limit(2)
            .collect(Collectors.toList());

        if (midPoints.size() < 2) {
            throw new RuntimeException("Long distance 2 mode requires 2 midpoint checkpoints to be configured");
        }
        Checkpoint midPoint1 = midPoints.get(0);
        Checkpoint midPoint2 = midPoints.get(1);

        boolean rosterActive = raceEntryRepository.existsByRaceId(raceId);
        List<Participant> participants = participantsForResults(raceId, rosterActive);
        int processed = 0;
        int completed = 0;

        for (Participant participant : participants) {
            try {
                String rfidTag = participant.getRfidTag();
                if (rfidTag == null || rfidTag.trim().isEmpty()) {
                    if (saveNoShowIfRostered(rosterActive, participant, raceId)) processed++;
                    continue;
                }

                List<CheckpointReading> readings = readingsSinceAttemptStart(rfidTag, raceId, attemptStart);

                if (readings.isEmpty()) {
                    if (saveNoShowIfRostered(rosterActive, participant, raceId)) processed++;
                    continue;
                }

                Optional<CheckpointReading> mid1Reading = findReadingAtCheckpoint(readings, midPoint1);
                Optional<CheckpointReading> mid2Reading = findReadingAtCheckpoint(readings, midPoint2);
                Optional<CheckpointReading> finishReading = findReadingAtCheckpoint(readings, finishLine);

                RaceResult result = new RaceResult();
                result.setRaceSessionId(sessionId);
                result.setParticipant(participant);
                result.setRaceId(raceId);
                result.setRfidTag(rfidTag);
                result.setStartTime(startTime);
                result.setCheckpointCount(readings.size());

                if (mid1Reading.isPresent() && mid2Reading.isPresent() && finishReading.isPresent()) {
                    LocalDateTime finishTime = finishReading.get().getReadTime();
                    Duration netTime = Duration.between(startTime, finishTime);

                    result.setFinishTime(finishTime);
                    result.setNetTimeSeconds(netTime.getSeconds());
                    result.setFormattedTime(formatDuration(netTime));
                    result.setCompletedRace(true);

                    Map<String, String> splits = new LinkedHashMap<>();
                    splits.put("Midpoint 1", formatDuration(Duration.between(startTime, mid1Reading.get().getReadTime())));
                    splits.put("Midpoint 2", formatDuration(Duration.between(startTime, mid2Reading.get().getReadTime())));
                    splits.put("Finish", formatDuration(netTime));
                    result.setSplitTimesJson(splits.toString());
                    completed++;
                } else {
                    result.setCompletedRace(false);
                    result.setFormattedTime("DNF");
                }

                resultRepository.save(result);
                processed++;
            } catch (Exception e) {
                log.error("Error calculating long distance 2 result: {}", e.getMessage());
            }
        }

        log.info("✅ Long distance 2 calculation completed: {} processed, {} completed", processed, completed);
    }

    /**
     * ✅✅✅ NEW: Calculate individual participant result for single file mode
     */
    private RaceResult calculateSingleFileParticipantResult(
        Participant participant,
        Long raceId,
        LocalDateTime manualStartTime,
        Checkpoint finishLine
    ) {
        Long sessionId = resolveSessionId(raceId);
        String rfidTag = participant.getRfidTag();
        
        if (rfidTag == null || rfidTag.trim().isEmpty()) {
            log.warn("⚠️ Participant {} has no RFID tag", participant.getName());
            return null;
        }

        // Get finish line reading for this participant
        List<CheckpointReading> finishReadings = readingRepository
            .findByRfidTagAndRaceIdOrderByReadTimeAsc(rfidTag, raceId);
        
        if (finishReadings.isEmpty()) {
            // DNF - Not found in finish file
            log.debug("⚠️ {} - Not found in finish line file (DNF)", participant.getName());
            
            RaceResult result = new RaceResult();
            result.setRaceSessionId(sessionId);
            result.setParticipant(participant);
            result.setRaceId(raceId);
            result.setRfidTag(rfidTag);
            result.setCheckpointCount(0);
            result.setCompletedRace(false);
            result.setFormattedTime("DNF");
            
            return result;
        }

        // Get first (or only) finish reading
        CheckpointReading finishReading = finishReadings.get(0);
        LocalDateTime finishTime = finishReading.getReadTime();
        
        // Calculate net time: finish time - manual start time
        Duration netTime = Duration.between(manualStartTime, finishTime);
        
        // Create completed result
        RaceResult result = new RaceResult();
        result.setRaceSessionId(sessionId);
        result.setParticipant(participant);
        result.setRaceId(raceId);
        result.setRfidTag(rfidTag);
        result.setStartTime(manualStartTime); // Manual start time
        result.setFinishTime(finishTime);
        result.setNetTimeSeconds(netTime.getSeconds());
        result.setFormattedTime(formatDuration(netTime));
        result.setCompletedRace(true);
        result.setCheckpointCount(1); // Only finish line
        
        log.info("✅ {} completed in {} (Start: {}, Finish: {})", 
            participant.getName(), 
            result.getFormattedTime(),
            manualStartTime.toLocalTime(),
            finishTime.toLocalTime());
        
        return result;
    }

    /**
     * MANUAL_START_DUAL_TIMING: two timings per runner, side by side.
     *
     * <ul>
     *   <li>Declared Start - the ONE date+time the operator declared for the whole race.</li>
     *   <li>Start Time - this runner's own first scan at the start-line checkpoint (captured
     *       exactly as in the other modes: {@link #findReadingAtCheckpoint}).</li>
     *   <li>Finish Time - this runner's scan at the finish-line checkpoint.</li>
     *   <li>Duration (Start Time), stored as netTimeSeconds/formattedTime = Finish - Start Time.
     *       The Declared-Start duration (Finish - Declared Start) is derived from the stored
     *       declaredStartTime + finishTime when displayed.</li>
     * </ul>
     * COMPLETED only when both a start scan and a finish scan exist; otherwise the usual
     * DNF / DNF (No-Show) convention. No ranking is assigned - this mode has no Rank/Winner.
     */
    @Transactional
    public void calculateManualStartDualTimingResults(Long raceId, LocalDateTime declaredStart, LocalDateTime attemptStart) {
        Long sessionId = resolveSessionId(raceId);
        log.info("🧮 Calculating MANUAL START (dual timing) results for race {} - declared start {}", raceId, declaredStart);

        List<Checkpoint> required = getRequiredCheckpoints(raceId, Race.CalculationMode.TWO_CHECKPOINT);
        if (required.isEmpty()) {
            throw new RuntimeException("No checkpoints configured for calculation");
        }
        Checkpoint startLine = required.get(0);
        Checkpoint finishLine = required.get(required.size() - 1);
        if (Objects.equals(startLine.getName(), finishLine.getName())) {
            throw new RuntimeException("Manual start mode needs a start checkpoint AND a separate finish checkpoint - "
                + "enable two locations and tick Start on one and Finish on the other");
        }

        boolean rosterActive = raceEntryRepository.existsByRaceId(raceId);
        List<Participant> participants = participantsForResults(raceId, rosterActive);
        int processed = 0;
        int completed = 0;

        for (Participant participant : participants) {
            try {
                String rfidTag = participant.getRfidTag();
                if (rfidTag == null || rfidTag.trim().isEmpty()) {
                    if (saveNoShowIfRostered(rosterActive, participant, raceId)) processed++;
                    continue;
                }

                List<CheckpointReading> readings = readingsSinceAttemptStart(rfidTag, raceId, attemptStart);
                if (readings.isEmpty()) {
                    if (saveNoShowIfRostered(rosterActive, participant, raceId)) processed++;
                    continue;
                }

                Optional<CheckpointReading> startReading = findReadingAtCheckpoint(readings, startLine);
                Optional<CheckpointReading> finishReading = findReadingAtCheckpoint(readings, finishLine);

                RaceResult result = new RaceResult();
                result.setRaceSessionId(sessionId);
                result.setParticipant(participant);
                result.setRaceId(raceId);
                result.setRfidTag(rfidTag);
                result.setCheckpointCount(readings.size());
                result.setDeclaredStartTime(declaredStart);
                startReading.ifPresent(r -> result.setStartTime(r.getReadTime()));
                finishReading.ifPresent(r -> result.setFinishTime(r.getReadTime()));

                if (startReading.isPresent() && finishReading.isPresent()) {
                    Duration chipTime = Duration.between(result.getStartTime(), result.getFinishTime());
                    result.setNetTimeSeconds(chipTime.getSeconds());
                    result.setFormattedTime(formatDuration(chipTime));
                    result.setCompletedRace(true);
                    completed++;
                } else {
                    result.setCompletedRace(false);
                    result.setFormattedTime("DNF");
                }

                resultRepository.save(result);
                processed++;
            } catch (Exception e) {
                log.error("Error calculating manual start (dual timing) result for {}: {}",
                    participant.getName(), e.getMessage());
            }
        }

        log.info("✅ Manual start (dual timing) calculation completed: {} processed, {} completed", processed, completed);
    }

    /**
     * Assign rankings after calculation ⭐
     */
    @Transactional
    public void assignRankings(Long raceId) {
        log.info("🏆 Assigning rankings for race {}", raceId);

        // Scoped to the CURRENT attempt only - without this, ranking by raceId alone
        // pulls in every past attempt's completed results too (Reset no longer
        // deletes them), so a fresh 5-finisher race would rank as 4th-8th instead of
        // 1st-5th if 3 older completed rows already exist for this raceId. There is no
        // whole-race fallback: with no current attempt resolveSessionId throws.
        Long sessionId = resolveSessionId(raceId);
        List<RaceResult> completedResults =
                resultRepository.findByRaceSessionIdAndCompletedRaceTrueOrderByNetTimeSecondsAsc(sessionId);
        
        // Assign overall rankings
        int overallRank = 1;
        for (RaceResult result : completedResults) {
            result.setOverallRank(overallRank++);
        }
        
        // Assign category rankings (by race category)
        Map<String, List<RaceResult>> byCategory = completedResults.stream()
            .collect(Collectors.groupingBy(r -> 
                r.getParticipant().getCategory() != null ? 
                r.getParticipant().getCategory() : "Unknown"
            ));
        
        for (Map.Entry<String, List<RaceResult>> entry : byCategory.entrySet()) {
            int categoryRank = 1;
            for (RaceResult result : entry.getValue()) {
                result.setCategoryRank(categoryRank++);
            }
        }
        
        // Assign age group rankings
        Map<String, List<RaceResult>> byAgeGroup = completedResults.stream()
            .collect(Collectors.groupingBy(r -> r.getParticipant().getAgeGroup()));
        
        for (Map.Entry<String, List<RaceResult>> entry : byAgeGroup.entrySet()) {
            int ageGroupRank = 1;
            for (RaceResult result : entry.getValue()) {
                result.setAgeGroupRank(ageGroupRank++);
            }
        }
        
        // Save all rankings
        resultRepository.saveAll(completedResults);
        
        log.info("✅ Rankings assigned: {} finishers", completedResults.size());
    }

    /**
     * Get live results (for display during race)
     */
    public List<RaceResult> getLiveResults(Long raceId) {
        return resultRepository.findByRaceIdOrderByNetTimeSecondsAsc(raceId);
    }

    /**
     * Get DNF list ⭐
     */
    public List<RaceResult> getDNFList(Long raceId) {
        List<RaceResult> allResults = resultRepository.findByRaceIdOrderByNetTimeSecondsAsc(raceId);
        return allResults.stream()
            .filter(r -> !r.isCompletedRace())
            .collect(Collectors.toList());
    }

    /**
     * Get completion statistics
     */
    public CompletionStats getCompletionStats(Long raceId) {
        long total = resultRepository.countByRaceId(raceId);
        long completed = resultRepository.countByRaceIdAndCompletedRaceTrue(raceId);
        long dnf = total - completed;
        
        CompletionStats stats = new CompletionStats();
        stats.setTotalParticipants((int) total);
        stats.setCompleted((int) completed);
        stats.setDnf((int) dnf);
        stats.setCompletionRate(total > 0 ? (completed * 100.0 / total) : 0.0);
        
        return stats;
    }

    // Inner class for completion statistics
    public static class CompletionStats {
        private int totalParticipants;
        private int completed;
        private int dnf;
        private double completionRate;

        public int getTotalParticipants() { return totalParticipants; }
        public void setTotalParticipants(int totalParticipants) { this.totalParticipants = totalParticipants; }
        
        public int getCompleted() { return completed; }
        public void setCompleted(int completed) { this.completed = completed; }
        
        public int getDnf() { return dnf; }
        public void setDnf(int dnf) { this.dnf = dnf; }
        
        public double getCompletionRate() { return completionRate; }
        public void setCompletionRate(double completionRate) { this.completionRate = completionRate; }
        
        @Override
        public String toString() {
            return String.format("Total: %d | Completed: %d | DNF: %d | Rate: %.1f%%",
                totalParticipants, completed, dnf, completionRate);
        }
    }
}
