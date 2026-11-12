package org.carddemo.batch.internal;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The internal API that Airflow calls (batch-execution-contract.md); the token filter guards it. */
@RestController
@RequestMapping("/internal")
class InternalController {

    private final Cycles cycles;
    private final BatchWindows windows;
    private final JobLaunches launches;
    private final ExecutionViews views;

    InternalController(Cycles cycles, BatchWindows windows, JobLaunches launches, ExecutionViews views) {
        this.cycles = cycles;
        this.windows = windows;
        this.launches = launches;
        this.views = views;
    }

    @PostMapping("/cycles")
    ResponseEntity<Map<String, Object>> registerCycle(@RequestBody(required = false) Map<String, Object> request) {
        Cycles.Registered registered = cycles.register(request);
        return ResponseEntity.status(registered.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(registered.cycle().body());
    }

    @PostMapping("/jobs/{jobName}/executions")
    ResponseEntity<Map<String, Object>> launch(@PathVariable String jobName,
            @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(launches.launch(jobName, request).body());
    }

    @GetMapping("/jobs/executions/{executionId}")
    Map<String, Object> execution(@PathVariable String executionId) {
        if (!executionId.matches("[0-9]{1,18}")) {
            throw new InternalApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "No job execution has this ID.", null);
        }
        return views.view(Long.parseLong(executionId));
    }

    @PostMapping("/batch-window/open")
    Map<String, Object> openWindow(@RequestBody(required = false) Map<String, Object> request) {
        return windows.open(WindowHolder.of(request));
    }

    @PostMapping("/batch-window/close")
    Map<String, Object> closeWindow(@RequestBody(required = false) Map<String, Object> request) {
        WindowHolder holder = WindowHolder.of(request);
        Object complete = request.get("complete");
        return windows.close(holder, complete == null ? null : RequestValues.forRequest().bool(complete, "complete"));
    }
}
