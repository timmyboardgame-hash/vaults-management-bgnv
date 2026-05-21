package com.vault.controller.web;

import com.vault.service.AgentService;
import com.vault.service.BookingService;
import com.vault.service.ItemService;
import com.vault.service.VaultService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeWebController {

    private final AgentService agentService;
    private final VaultService vaultService;
    private final ItemService itemService;
    private final BookingService bookingService;

    public HomeWebController(AgentService agentService, VaultService vaultService,
                             ItemService itemService, BookingService bookingService) {
        this.agentService = agentService;
        this.vaultService = vaultService;
        this.itemService = itemService;
        this.bookingService = bookingService;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("agentCount",   agentService.listAgents().size());
        model.addAttribute("vaultCount",   vaultService.listVaults().size());
        model.addAttribute("itemCount",    itemService.listItems().size());
        model.addAttribute("bookingCount", bookingService.getAllBookings().size());
        return "home";
    }
}
