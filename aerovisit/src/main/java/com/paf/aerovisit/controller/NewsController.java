package com.paf.aerovisit.controller;

import com.paf.aerovisit.service.NewsService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

// 📰 The public News feed, read-only: one page (5 posts) of published posts as an HTML fragment that news.js appends.
// Rendering on the server means every caption goes through th:text (escaped); the page size is fixed here, never by the
// browser. Public GET only in SecurityConfig, rate-limited per IP in RateLimitFilter ("news-feed").
@Controller
public class NewsController {

    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @GetMapping("/news/feed")
    public String feed(@RequestParam(value = "page", defaultValue = "0") String page, Model model) {
        int number;
        try {
            number = Integer.parseInt(page.trim());
        } catch (NumberFormatException e) {
            number = 0;
        }
        model.addAttribute("feed", newsService.feedPage(number));
        return "fragments/news :: feed";
    }
}
