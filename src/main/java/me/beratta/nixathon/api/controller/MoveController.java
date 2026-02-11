package me.beratta.nixathon.api.controller;

import me.beratta.nixathon.game.dto.CombatInput;
import me.beratta.nixathon.game.dto.NegotiateInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MoveController {

    private static final Logger log = LoggerFactory.getLogger(MoveController.class);

    @PostMapping("/negotiate")
    public ResponseEntity pickMove(@RequestBody NegotiateInput request) {
        log.info("Received negotiate request {}",request);

        return  ResponseEntity.ok().build();
    }

    @PostMapping("/combat")
    public ResponseEntity mortalCOMBAAAAAAAAAAAAAAAAAAAT(@RequestBody CombatInput request){
        log.info("Received combat request {}",request);
        return ResponseEntity.ok().build();
    }
}
