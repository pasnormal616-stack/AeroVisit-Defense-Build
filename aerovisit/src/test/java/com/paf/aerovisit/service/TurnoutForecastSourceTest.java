package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.MuseumPrediction;
import com.sun.net.httpserver.HttpServer;

/**
 * The AI service says whether its walk-in forecast is a starter estimate ("data_source": "baseline", fewer than 14 days
 * of visit history) or learned from real check-ins ("live"). A local stub server; no Python, no database.
 */
class TurnoutForecastSourceTest {

    private HttpServer stub;
    private final PredictiveAdvisorService service = new PredictiveAdvisorService();

    private MuseumPrediction forecastAnswering(String dataSourceField) throws IOException {
        String json = "{\"total_prediction\":50,\"walk_ins\":40,\"min_walk_ins\":30,\"max_walk_ins\":50,"
                + "\"confidence_percentage\":55," + dataSourceField + "\"weather\":\"Clear\","
                + "\"briefing\":{\"security_condition\":\"CONDITION GREEN\",\"personnel\":\"2 Guards\",\"facility_protocol\":\"Standard\"}}";
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        service.apiBaseUrl = "http://127.0.0.1:" + stub.getAddress().getPort();
        return service.fetchTurnoutPrediction(6, "Clear", 10);
    }

    @AfterEach
    void stop() {
        if (stub != null) stub.stop(0);
    }

    @Test
    void aStarterEstimateIsMarkedAsBaseline() throws IOException {
        MuseumPrediction prediction = forecastAnswering("\"data_source\":\"baseline\",");
        assertThat(prediction.getWalkIns()).isEqualTo(40);
        assertThat(prediction.isBaselineEstimate()).isTrue();
    }

    @Test
    void aLearnedForecastIsNotMarkedAsBaseline() throws IOException {
        assertThat(forecastAnswering("\"data_source\":\"live\",").isBaselineEstimate()).isFalse();
    }

    @Test
    void anOlderAiServiceWithoutTheFieldIsNotMarkedAsBaseline() throws IOException {
        assertThat(forecastAnswering("").isBaselineEstimate()).isFalse();
    }
}
