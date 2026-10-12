package com.paf.aerovisit.scheduler;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.PredictiveAdvisorService;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@EnableScheduling
public class MLOpsScheduler {

    private final VisitorRepository repo;
    private final PredictiveAdvisorService aiService;

    public MLOpsScheduler(VisitorRepository repo, PredictiveAdvisorService aiService) {
        this.repo = repo;
        this.aiService = aiService;
    }

    // Runs automatically every night at 11:59 PM
    @Scheduled(cron = "0 59 23 * * ?")
    public void auditDailyAiAccuracy() {
        LocalDate today = LocalDate.now();
        System.out.println("🤖 [MLOps] Initiating Nightly AI Accuracy Audit for: " + today);

        try {
            // 1. Get ACTUAL turnout from the gates
            List<Visitor> todayVisitors = repo.findByVisitDate(today);
            int actualTurnout = todayVisitors.stream()
                    .filter(v -> "CHECKED-IN".equals(v.getStatus()) || "CHECKED-OUT".equals(v.getStatus()))
                    .mapToInt(v -> v.getGroupSize() > 0 ? v.getGroupSize() : 1)
                    .sum();

            // 2. Safely filter for pre-booked visitors using status instead of lead time
            int actualPreBooked = todayVisitors.stream()
                    .filter(v -> "APPROVED".equals(v.getStatus()) || "CHECKED-IN".equals(v.getStatus()) || "CHECKED-OUT".equals(v.getStatus()) || "NO_SHOW".equals(v.getStatus())) // a no-show was still pre-booked
                    .mapToInt(v -> v.getGroupSize() > 0 ? v.getGroupSize() : 1)
                    .sum();

            // 3. Ask AI what it originally predicted for today
            int dayOfWeek = today.getDayOfWeek().getValue() - 1;
            var prediction = aiService.fetchTurnoutPrediction(dayOfWeek, "", actualPreBooked);

            // 4. Calculate Variance (No longer needs string parsing since it is an int)
            if (prediction != null) {
                int predictedTurnout = prediction.getTotalPrediction();
                int variance = actualTurnout - predictedTurnout;
                double errorMargin = (predictedTurnout == 0) ? 0 : ((double) Math.abs(variance) / predictedTurnout) * 100;

                System.out.println("📊 [MLOps] Actual: " + actualTurnout + " | Predicted: " + predictedTurnout);
                System.out.println("📉 [MLOps] Variance: " + variance + " (" + String.format("%.2f", errorMargin) + "% Error Rate)");
                
                if (errorMargin > 20.0) {
                    System.out.println("⚠️ [MLOps ALERT] High variance detected. Flagging date for next model retraining cycle.");
                }
            }
        } catch (Exception e) {
            System.out.println("❌ [MLOps ERROR] Audit failed: " + e.getMessage());
        }
    }
}