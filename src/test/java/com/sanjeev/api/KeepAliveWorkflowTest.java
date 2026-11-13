package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guard rails for the backup keep-alive workflow (.github/workflows/keep-alive.yml), which pings the
 * free Render demo so it does not fall asleep. A workflow file is not Java, so these tests read it as
 * text and check the few rules that must never change by accident.
 */
class KeepAliveWorkflowTest {

    private static final Path WORKFLOW = Path.of(".github", "workflows", "keep-alive.yml");
    private static final String HEALTH_URL = "https://matching-engine-hwru.onrender.com/actuator/health";

    private static String workflow() throws IOException {
        return Files.readString(WORKFLOW, StandardCharsets.UTF_8);
    }

    private static List<String> urlsIn(String text) {
        List<String> urls = new ArrayList<>();
        Matcher matcher = Pattern.compile("https?://[^\\s\"'\\\\]+").matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        return urls;
    }

    @Test
    void theWorkflowFileExists() {
        assertTrue(Files.isRegularFile(WORKFLOW), "missing " + WORKFLOW);
    }

    @Test
    void itRunsEveryTenMinutesAndCanAlsoBeStartedByHand() throws IOException {
        String text = workflow();

        assertTrue(text.contains("workflow_dispatch:"), "no manual trigger");
        Matcher cron = Pattern.compile("cron:\\s*\"(\\d+)-59/10 \\* \\* \\* \\*\"").matcher(text);
        assertTrue(cron.find(), "the schedule must be 'START-59/10 * * * *' (every 10 minutes)");
        int start = Integer.parseInt(cron.group(1));
        List<Integer> minutes = new ArrayList<>();
        for (int minute = start; minute <= 59; minute += 10) {
            minutes.add(minute);
        }
        assertEquals(6, minutes.size(), "six runs an hour, ten minutes apart: " + minutes);
        assertFalse(minutes.contains(0) || minutes.contains(30),
                "avoid :00 and :30, when GitHub's scheduler is busiest and most likely to delay or drop runs: " + minutes);
    }

    @Test
    void itPingsOnlyTheHealthEndpointOfTheDemo() throws IOException {
        List<String> urls = urlsIn(workflow());

        assertFalse(urls.isEmpty(), "no URL found");
        for (String url : urls) {
            assertEquals(HEALTH_URL, url, "the workflow must call only the health endpoint");
        }
    }

    @Test
    void itNeverPingsRobotsTxtBecauseRenderAnswersThatItselfWithoutWakingTheApp() throws IOException {
        for (String url : urlsIn(workflow())) {
            assertFalse(url.toLowerCase().contains("robots"), "robots.txt does not wake the app: " + url);
        }
    }

    @Test
    void aHungRequestCannotRunForeverAndAnErrorStatusFailsTheRun() throws IOException {
        String text = workflow();

        assertTrue(text.contains("--max-time"), "curl needs a time limit");
        assertTrue(text.contains("timeout-minutes"), "the job needs a time limit");
        assertTrue(text.contains("--fail"), "an error status must make the run fail, so it is visible");
    }

    @Test
    void itNeedsNoSecretsAndNoTokenPermissions() throws IOException {
        String text = workflow();

        assertFalse(Pattern.compile("\\$\\{\\{[^}]*secrets\\.").matcher(text).find(),
                "the URL is public: no ${{ secrets.* }} reference is needed");
        assertTrue(text.contains("permissions: {}"), "give the workflow no token permissions at all");
    }

    @Test
    void forksOfTheRepositoryDoNotPingTheDemo() throws IOException {
        assertTrue(workflow().contains("github.repository == 'sanjeevveerapandian/matching-engine'"));
    }
}
