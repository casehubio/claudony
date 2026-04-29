package dev.claudony.e2e;

import com.microsoft.playwright.*;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;

/**
 * Abstract base for all Playwright browser E2E tests.
 *
 * <p>Manages Playwright, Browser, BrowserContext, and Page lifecycle:
 * - Playwright + Browser created once per test class (@BeforeAll/@AfterAll)
 * - BrowserContext + Page created fresh per test (@BeforeEach/@AfterEach) — no state bleed
 *
 * <p>Auth: every request the page makes (page loads, fetch() calls) gets the test API key
 * via page.setExtraHTTPHeaders(). WebSocket connections do NOT inherit extra headers —
 * terminal WebSocket tests require a different auth strategy (deferred).
 *
 * <p>Headless by default. Run with -Dplaywright.headless=false for a visible browser.
 */
public abstract class PlaywrightBase {

    protected static Playwright playwright;
    protected static Browser browser;
    protected BrowserContext context;
    protected Page page;

    /**
     * Base URL for the Quarkus test server.
     *
     * <p>Initialised in {@link #launchBrowser()} from the {@code test.url} MicroProfile Config
     * value, which Quarkus sets to the actual bound URL after startup (correct even when
     * {@code quarkus.http.test-port=0} assigns a random port). Do not read this before
     * {@code @BeforeAll} — it will be null.
     */
    protected static String BASE_URL;

    /** Test API key — matches %test.claudony.agent.api-key in application.properties. */
    protected static final String API_KEY = "test-api-key-do-not-use-in-prod";

    @BeforeAll
    static void launchBrowser() {
        BASE_URL = ConfigProvider.getConfig().getValue("test.url", String.class);
        playwright = Playwright.create();
        var headless = Boolean.parseBoolean(System.getProperty("playwright.headless", "true"));
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(headless));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void createContextAndPage() {
        context = browser.newContext();
        page = context.newPage();
        // Inject API key into every HTTP request the page makes
        page.setExtraHTTPHeaders(Map.of("X-Api-Key", API_KEY));
    }

    @AfterEach
    void closeContext() {
        if (context != null) context.close();
    }
}
