package com.paf.aerovisit.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class WeatherService {

    // Pulls the active API key from application.properties
    @Value("${openweathermap.api.key}")
    private String apiKey;

    // Pulls the base URL from application.properties
    @Value("${openweathermap.api.url}")
    private String apiUrl;

    public String getLiveWeather() {
        try {
            // Target location set to Pasay, PH with metric units for Celsius
            String city = "Pasay,PH";
            String requestUrl = apiUrl + "?q=" + city + "&appid=" + apiKey + "&units=metric";

            // Make the HTTP GET request to OpenWeatherMap
            RestTemplate restTemplate = new RestTemplate();
            String response = restTemplate.getForObject(requestUrl, String.class);

            // Parse the JSON response to grab exactly what we need
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response);
            
            double temp = root.path("main").path("temp").asDouble();
            String description = root.path("weather").get(0).path("description").asText();

            // Capitalize the first letter of the description for a cleaner UI
            description = description.substring(0, 1).toUpperCase() + description.substring(1);

            // Returns a clean string like "32.5°C - Broken clouds"
            return String.format("%.1f°C - %s", temp, description);

        } catch (Exception e) {
            // If the API key is still activating or the internet drops, 
            // catch the error silently so the app doesn't crash.
            System.out.println("Weather AI Error: " + e.getMessage());
            return "Weather Data Currently Unavailable";
        }
    }
}