package me.beratta.nixathon.game.service;

import me.beratta.nixathon.game.dto.MoveResponse;
import org.springframework.stereotype.Service;

@Service
public class MoveStrategyService {

    public MoveResponse chooseMove() {
        return new MoveResponse();
    }

}
