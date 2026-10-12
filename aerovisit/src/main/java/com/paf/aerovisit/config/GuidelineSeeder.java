package com.paf.aerovisit.config;

import com.paf.aerovisit.model.Guideline;
import com.paf.aerovisit.repository.GuidelineRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// First start only: fills the guidelines table with the two rules the Visitor Guidelines tab
// used to hardcode ("Identification" and "Dress Code"), so the portal never starts out empty.
@Component
public class GuidelineSeeder implements ApplicationRunner {

    private final GuidelineRepository guidelineRepo;

    public GuidelineSeeder(GuidelineRepository guidelineRepo) {
        this.guidelineRepo = guidelineRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (guidelineRepo.count() > 0) return;
        guidelineRepo.saveAll(Guideline.defaults());
    }
}
