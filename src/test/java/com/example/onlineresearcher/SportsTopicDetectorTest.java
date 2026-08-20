package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SportsTopicDetectorTest {

    @Test
    void detectsSportsScoreQuestions() {
        assertTrue(SportsTopicDetector.isSportsScore(
                "What was the score in the recent world cup game between Japan and Brazil?"));
        assertTrue(SportsTopicDetector.isSportsScore("who won the Lakers vs Celtics game last night"));
        assertTrue(SportsTopicDetector.isSportsScore("final score of the champions league final"));
        assertTrue(SportsTopicDetector.isSportsScore("how many goals did Messi score in the match"));
    }

    @Test
    void ignoresNonSportsUsesOfScoreAndResults() {
        assertFalse(SportsTopicDetector.isSportsScore("how do I improve my credit score"));
        assertFalse(SportsTopicDetector.isSportsScore("what was my SAT test score range"));
        assertFalse(SportsTopicDetector.isSportsScore("the relation between Jacobsthal number and function"));
        assertFalse(SportsTopicDetector.isSportsScore("health benefits of green tea"));
    }

    @Test
    void requiresBothIntentAndSportsContext() {
        // sports context but no score/result intent
        assertFalse(SportsTopicDetector.isSportsScore("history of the football world cup"));
        // score intent but no sports context
        assertFalse(SportsTopicDetector.isSportsScore("what is a good score on a piano exam"));
    }
}
