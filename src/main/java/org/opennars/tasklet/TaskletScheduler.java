package org.opennars.tasklet;

import org.opennars.DerivationProcessor;
import org.opennars.control.DerivationContext;
import org.opennars.entity.Sentence;
import org.opennars.entity.Stamp;
import org.opennars.entity.Task;
import org.opennars.interfaces.Timable;
import org.opennars.io.Symbols;
import org.opennars.language.Inheritance;
import org.opennars.language.Similarity;
import org.opennars.language.Term;
import org.opennars.main.Parameters;
import org.opennars.storage.Bag;

import java.util.*;

// TODO< feed back the sentence as a derived task to the reasoner with a reason which prevents a immediate feedback >
// TODO< avoid to derive the same premises >

public class TaskletScheduler {
    private List<Tasklet> secondary; // sequences and other compositions
    private List<Tasklet> secondarySingleEvents;

    private Random rng = new Random(43); // with seed for debugging and testing of core - we hit a lot of unsupported cases in temporal induction because the preconditions are loosened

    private long dbgStep = 0; // for debugging - current step number

    public TaskletScheduler(Parameters reasonerParameters) {
        secondary = new ArrayList<>();
        secondarySingleEvents = new ArrayList<>();
    }

    private static boolean isEvent(Sentence sentence) {
        final Term term = sentence.term;

        if(sentence.isEternal()) {
            return false;
        }

        return term instanceof Similarity || term instanceof Inheritance;
    }

    // /param addedToMemory was the task added to memory?
    public void addTaskletByTask(Task task, EnumAddedToMemory addedToMemory, Timable timable) {
        addTasklet(new Tasklet(task, timable));
    }

    private void addTasklet(Tasklet tasklet) {
        Sentence sentence = tasklet.isBelief() ? tasklet.belief : tasklet.task.sentence;

        if (isEvent(sentence)) {
            secondarySingleEvents.add(0, tasklet);
        }
        else {
            secondary.add(0, tasklet);
        }
    }

    public void iterate(Timable timable, DerivationContext nal) {
        System.out.println("step=" + dbgStep);

        indicesAlreadySampled.clear();
        for(int iteration=0;iteration < 100; iteration++) {
            sample(secondarySingleEvents, secondarySingleEvents, timable, nal);
        }

        indicesAlreadySampled.clear();
        for(int iteration=0;iteration < 25; iteration++) {
            sample(secondary, secondarySingleEvents, timable, nal);
        }

        sortByUtilityAndLimitSize(secondary, nal);
        sortByUtilityAndLimitSize(secondarySingleEvents, nal);

        int debugHere = 5;

        dbgStep++;
    }

    private void sortByUtilityAndLimitSize(List<Tasklet> tasklets, DerivationContext nal) {
        // recalc utilities
        for(int idx=0;idx<tasklets.size();idx++) {
            tasklets.get(idx).calcUtility(nal.time);
        }

        // TODO< find items which utility is not sorted and insert them again in the right places >
        Collections.sort(tasklets, (s1, s2) -> { return s1.cachedUtility == s2.cachedUtility ? 0 : ( s1.cachedUtility < s2.cachedUtility ? 1 : -1 ); });

        while (tasklets.size() > 20000) {
            tasklets.remove(20000-1);
        }
    }

    private Map<Tuple, Boolean> indicesAlreadySampled = new HashMap<>();

    private void sample(List<Tasklet> taskletsA, List<Tasklet> taskletsB, Timable timable, DerivationContext nal) {
        final boolean areSameTaskletLists = taskletsA == taskletsB;

        if(areSameTaskletLists && taskletsA.size() < 2) {
            return;
        }
        else if(taskletsA.size() < 1 || taskletsB.size() < 1) {
            return;
        }

        int idxA, idxB;
        for(;;) {
            idxA = rng.nextInt(taskletsA.size());
            idxB = rng.nextInt(taskletsB.size());

            if(areSameTaskletLists) {
                if(idxA != idxB) {
                    break;
                }
            }
            else {
                break;
            }
        }

        // avoid sampling the same multiple times
        if(indicesAlreadySampled.containsKey(new Tuple(idxA, idxB))) {
            return; // ignore sample
        }
        if(areSameTaskletLists && indicesAlreadySampled.containsKey(new Tuple(idxB, idxA))) {
            return; // ignore sample
        }
        indicesAlreadySampled.put(new Tuple(idxA, idxB), true);

        Tasklet taskletA = taskletsA.get(idxA);
        Tasklet taskletB = taskletsB.get(idxB);

        combine(taskletA, taskletB, timable, nal);

    }

    // combines and infers two tasklets
    private void combine(Tasklet a, Tasklet b, Timable timable, DerivationContext nal) {
        if (a.isTask() && b.isBelief()) {
            combine2(a.task.sentence, a.task.isInput(), b.belief, false/* don't know */, timable, nal);
        }
        else if(a.isBelief() && b.isTask()) {
            combine2(a.belief, false/* don't know */, b.task.sentence, b.task.isInput(), timable, nal);
        }
        else if(a.isTask() && b.isTask()) {
            combine2(a.task.sentence, a.task.isInput(), b.task.sentence, b.task.isInput(), timable, nal);
        }
        else {
            combine2(a.belief, false /* don't know */, b.belief, false /* don't know */, timable, nal);
        }
    }

    private void combine2(Sentence a, boolean aIsInput, Sentence b, boolean bIsInput, Timable timable, DerivationContext nal) {
        if (a.stamp.isEternal() || b.stamp.isEternal()) {
            return; // we can't combine eternal beliefs/tasks
        }

        // order a and b by time
        if(a.stamp.getOccurrenceTime() > b.stamp.getOccurrenceTime()) {
            Sentence temp = a;
            boolean tempInput = aIsInput;
            a = b;
            aIsInput = bIsInput;
            b = temp;
            bIsInput = tempInput;
        }

        if(b.punctuation!= Symbols.JUDGMENT_MARK && b.punctuation!=Symbols.GOAL_MARK) {
            return; // succeeding can be a judgement or goal
        }

        if(a.punctuation!= Symbols.JUDGMENT_MARK) {
            return;// temporal inductions for judgements  only
        }


        // b must be input
        if (!bIsInput) {
            return;
        }

        if( Stamp.baseOverlap(a.stamp, b.stamp)) {
            return; // we can't combine the two sentences of the tasklets!
        }


        //nal.setTheNewStamp(a.stamp, b.stamp, nal.time.time());
        //nal.setCurrentBelief(b);

        // addToMemory is a misnomer - should be renamed
        List<Sentence> derivedTerms = new ArrayList<>();

        {
            Sentence derivedSentence = DerivationProcessor.processProgramForTemporal("S", "E", DerivationProcessor.programCombineSequenceAndEvent, a, b, timable, nal.narParameters);
            if (derivedSentence != null) {
                derivedTerms.add(derivedSentence);
            }

            derivedSentence = DerivationProcessor.processProgramForTemporal("S", "E", DerivationProcessor.programCombineSequenceAndEvent, b, a, timable, nal.narParameters);
            if (derivedSentence != null) {
                derivedTerms.add(derivedSentence);
            }

            derivedSentence = DerivationProcessor.processProgramForTemporal("E", "E", DerivationProcessor.programCombineEventAndEvent, a, b, timable, nal.narParameters);
            if (derivedSentence != null) {
                derivedTerms.add(derivedSentence);
            }
        }

        if (derivedTerms.size() > 0) {
            System.out.println("combine2()");
            System.out.println("    " + a + " occTime=" + a.stamp.getOccurrenceTime());
            System.out.println("    " + b + " occTime=" + b.stamp.getOccurrenceTime());
        }


        //System.out.println("=====");
        //for(Task iDerivedTask : derivedTasks) {
        //    System.out.println("derived " + iDerivedTask.toString());
        //}
        for(Sentence iDerivedSentence : derivedTerms) {
            System.out.println("derived " + iDerivedSentence);
        }

        // create new tasklets from derived ones
        List<Tasklet> derivedTasklets = new ArrayList<>();
        for(Sentence iDerivedSentence : derivedTerms) {
            derivedTasklets.add(new Tasklet(iDerivedSentence, nal.time));
        }

        // TODO< rework to use a table with a utility function >
        for(Tasklet iDerivedTasklet : derivedTasklets) {
            addTasklet(iDerivedTasklet);
        }
    }

    public enum EnumAddedToMemory {
        YES,
        NO
    }

    private static class Tuple {
        public int a, b;

        public Tuple(int a, int b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public int hashCode() {
            return a + b;
        }
    }
}
