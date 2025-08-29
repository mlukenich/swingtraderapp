package com.app.swingtrader.controller;

import com.app.swingtrader.model.Position;
import com.app.swingtrader.model.PositionStatus;
import com.app.swingtrader.repository.PositionRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class WebController {

    private final PositionRepository positionRepository;

    public WebController(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    /**
     * Handles requests for the main dashboard page.
     * It fetches all open positions from the database and adds them to the model
     * so they can be displayed in the HTML template.
     */
    @GetMapping("/")
    public String dashboard(Model model) {
        // Find all positions with a status of OPEN
        List<Position> openPositions = positionRepository.findAllByStatus(PositionStatus.OPEN);

        // Add the list of positions to the model under the name "positions"
        model.addAttribute("positions", openPositions);

        // Return the name of the Thymeleaf template to render
        return "dashboard";
    }
}
