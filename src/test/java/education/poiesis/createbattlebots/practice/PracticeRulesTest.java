package education.poiesis.createbattlebots.practice;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PracticeRulesTest {
    @Test void opposingKeysCancel() { assertEquals(0, PracticeRules.decode(15).throttle()); assertEquals(0, PracticeRules.decode(15).turn()); }
    @Test void forwardAndRightAreIndependent() { var i=PracticeRules.decode(1|8|16); assertEquals(1,i.throttle()); assertEquals(1,i.turn()); assertTrue(i.weapon()); }
    @Test void invalidFlagsRejected() { assertThrows(IllegalArgumentException.class,()->PracticeRules.decode(64)); assertThrows(IllegalArgumentException.class,()->PracticeRules.decode(-1)); }
    @Test void inputIsFiniteAndBounded() { var i=new PracticeRules.Input(99,-12,false,false); assertEquals(1,i.throttle()); assertEquals(-1,i.turn()); assertThrows(IllegalArgumentException.class,()->new PracticeRules.Input(Double.NaN,0,false,false)); }
    @Test void restingContactsCauseNoDamage() { assertEquals(0,PracticeRules.damage(0)); assertEquals(0,PracticeRules.damage(1)); }
    @Test void invalidPhysicsCausesNoDamage() { assertEquals(0,PracticeRules.damage(Double.NaN)); assertEquals(0,PracticeRules.damage(Double.POSITIVE_INFINITY)); assertEquals(0,PracticeRules.damage(-3)); }
    @Test void damageIsMonotonicAndCapped() { assertTrue(PracticeRules.damage(8)>PracticeRules.damage(3)); assertEquals(22,PracticeRules.damage(100000)); }
    @Test void brainTurnsTowardOpponent() { var b=new PracticeRules.Brain(); var i=b.tick(0,0,0,10,0,0,0,false); assertTrue(i.turn()>0); assertTrue(i.weapon()); }
    @Test void brainDrivesTowardOpponentAhead() { var i=new PracticeRules.Brain().tick(0,0,0,0,10,0,0,false); assertEquals(1,i.throttle()); assertEquals(0,i.turn()); }
    @Test void rookieUsesSameControlsButLessThrottle() { var i=new PracticeRules.Brain().tick(0,0,0,0,10,0,0,true); assertEquals(.65,i.throttle()); }
    @Test void stuckBrainReversesWithoutTeleporting() { var b=new PracticeRules.Brain(); PracticeRules.Input i=null; for(int n=0;n<51;n++) i=b.tick(0,0,0,0,10,0,0,false); assertTrue(i.throttle()<0); assertNotEquals(0,i.turn()); }
    @Test void invalidAiStateStops() { assertEquals(PracticeRules.Input.STOP,new PracticeRules.Brain().tick(Double.NaN,0,0,0,1,0,0,false)); }
    @Test void angleWrapsToShortestTurn() { assertEquals(-.1,PracticeRules.angle(2*Math.PI-.1),1e-8); }
    @Test void nearEdgeAiSteersInward() { var i=new PracticeRules.Brain().tick(23,0,0,30,0,0,0,false); assertTrue(i.turn()<0); }
}
