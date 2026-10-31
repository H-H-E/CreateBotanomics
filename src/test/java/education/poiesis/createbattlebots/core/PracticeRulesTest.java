package education.poiesis.createbattlebots.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PracticeRulesTest {
    @Test void rejectsUntrustedForces(){
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,1.01,-1.01})
            assertThrows(IllegalArgumentException.class,()->new ArcadeInput(value,0,false));
        assertThrows(IllegalArgumentException.class,()->ArcadeInput.buttons(32));
        assertThrows(IllegalArgumentException.class,()->ArcadeInput.buttons(-1));
    }
    @Test void opposingKeysCancelAndWeaponIsIndependent(){
        assertEquals(new ArcadeInput(0,0,true),ArcadeInput.buttons(31));
        assertEquals(new ArcadeInput(1,1,false),ArcadeInput.buttons(5));
        assertEquals(new ArcadeInput(-1,-1,false),ArcadeInput.buttons(10));
    }
    @Test void focusLossOrMissingPacketsCannotLeaveThrottleStuck(){
        var moving=ArcadeInput.buttons(17);
        assertEquals(moving,ArcadeInput.fresh(moving,10,20));
        assertEquals(ArcadeInput.IDLE,ArcadeInput.fresh(moving,10,21));
        assertEquals(ArcadeInput.IDLE,ArcadeInput.fresh(moving,-1,0));
        assertEquals(ArcadeInput.IDLE,ArcadeInput.fresh(moving,10,9));
    }
    @Test void pilotTurnsTowardOpponentWithoutTeleporting(){
        var pilot=new ScrapbotPilot();
        assertTrue(pilot.decide(0,0,0,0,-1,-10,-5,0,0).steering()>0);
        assertTrue(pilot.decide(1,0,0,0,-1,10,-5,0,0).steering()<0);
        assertEquals(1,pilot.decide(2,0,0,0,-1,0,-10,0,0).throttle());
    }
    @Test void stuckPilotReversesForBoundedTime(){
        var pilot=new ScrapbotPilot();pilot.decide(0,0,0,0,-1,0,-10,0,0);
        pilot.decide(45,0,0,0,-1,0,-10,0,0);
        assertTrue(pilot.decide(46,0,0,0,-1,0,-10,0,0).throttle()<0);
        assertTrue(pilot.decide(70,0,0,0,-1,0,-10,0,0).throttle()>0);
    }
    @Test void pilotAvoidsFollowingTargetOffArena(){
        var pilot=new ScrapbotPilot();
        assertTrue(pilot.decide(1,16,0,1,0,40,0,0,0).throttle()<.5);
        assertEquals(ArcadeInput.IDLE,pilot.decide(2,Double.NaN,0,0,-1,0,0,0,0));
    }
    @Test void tutorialNeedsObservedMovementTurningAndWeaponSpeed(){
        var tutorial=new PracticeTutorial();tutorial.observe(0,0,0,0);
        for(int i=0;i<200;i++)tutorial.observe(0,0,0,0);
        assertEquals(0,tutorial.step());tutorial.observe(0,-3,0,0);assertEquals(1,tutorial.step());
        tutorial.observe(0,-3,.65,0);assertEquals(2,tutorial.step());
        tutorial.observe(0,-3,.65,2.1);assertEquals(3,tutorial.step());
    }
    @Test void tutorialRejectsInvalidTelemetryAndWrapsAngles(){
        var tutorial=new PracticeTutorial();tutorial.observe(Double.NaN,0,0,20);assertEquals(0,tutorial.step());
        tutorial.observe(0,0,3.1,0);tutorial.observe(4,0,3.1,0);tutorial.observe(4,0,-3.1,0);
        assertEquals(1,tutorial.step());tutorial.skip();assertEquals(3,tutorial.step());
    }
}
