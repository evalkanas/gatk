package org.broadinstitute.hellbender.tools.walkers.haplotypecaller;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.samtools.Cigar;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMReadGroupRecord;
import htsjdk.samtools.reference.ReferenceSequenceFile;
import htsjdk.samtools.util.Locatable;
import htsjdk.variant.variantcontext.*;
import htsjdk.variant.vcf.VCFConstants;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.gatk.nativebindings.smithwaterman.SWParameters;
import org.broadinstitute.hellbender.engine.AlignmentContext;
import org.broadinstitute.hellbender.engine.AssemblyRegion;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.FlowBasedArgumentCollection;
import org.broadinstitute.hellbender.tools.walkers.haplotypecaller.readthreading.ReadThreadingAssembler;
import org.broadinstitute.hellbender.utils.IndexRange;
import org.broadinstitute.hellbender.utils.QualityUtils;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.clipping.ReadClipper;
import org.broadinstitute.hellbender.utils.dragstr.DragstrParamUtils;
import org.broadinstitute.hellbender.utils.fragments.FragmentCollection;
import org.broadinstitute.hellbender.utils.fragments.FragmentUtils;
import org.broadinstitute.hellbender.utils.genotyper.AlleleLikelihoods;
import org.broadinstitute.hellbender.utils.genotyper.IndexedAlleleList;
import org.broadinstitute.hellbender.utils.genotyper.SampleList;
import org.broadinstitute.hellbender.utils.haplotype.Event;
import org.broadinstitute.hellbender.utils.haplotype.Haplotype;
import org.broadinstitute.hellbender.utils.haplotype.HaplotypeBAMWriter;
import org.broadinstitute.hellbender.utils.haplotype.PartiallyDeterminedHaplotype;
import org.broadinstitute.hellbender.utils.io.IOUtils;
import org.broadinstitute.hellbender.utils.locusiterator.LocusIteratorByState;

import org.broadinstitute.hellbender.utils.pileup.ReadPileup;
import org.broadinstitute.hellbender.utils.read.*;
import org.broadinstitute.hellbender.utils.smithwaterman.SmithWatermanAligner;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;
import org.broadinstitute.hellbender.utils.variant.GATKVariantContextUtils;

import java.io.File;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Created by davidben on 9/8/16.
 */
public final class AssemblyBasedCallerUtils {

    public static final int REFERENCE_PADDING_FOR_ASSEMBLY = 500;
    public static final int DETERMINE_COLLAPSE_THRESHOLD = -1;
    public static final int NUM_HAPLOTYPES_TO_INJECT_FORCE_CALLING_ALLELES_INTO = 5;
    public static final String SUPPORTED_ALLELES_TAG="XA";
    public static final String CALLABLE_REGION_TAG = "CR";
    public static final String ALIGNMENT_REGION_TAG = "AR";
    public static final String EXT_COLLAPSED_TAG = "XC";
    public static final String EXT_SPECIAL_TAG = "XS"; // added to haplotype to assist in reading them back in with all fields restored.

    public static final Function<Haplotype, Double> HAPLOTYPE_ALIGNMENT_TIEBREAKING_PRIORITY = h -> {
        final Cigar cigar = h.getCigar();
        final int referenceTerm = (h.isReference() ? 1 : 0);
        final int cigarTerm = cigar == null ? 0 : (1 - cigar.numCigarElements());
        return (double) referenceTerm + cigarTerm;
    };

    // After trimming to fit the assembly window, throw away read stubs shorter than this length
    // if we don't, the several bases left of reads that end just within the assembly window can
    // get realigned incorrectly.  See https://github.com/broadinstitute/gatk/issues/5060
    public static final int MINIMUM_READ_LENGTH_AFTER_TRIMMING = 10;

    // Phase group notation can be interpreted as a representation of the alleles present on the two phased haplotypes at the site:
    // "0": REF or '*'; "1": site-specific alt allele
    enum PhaseGroup {
        PHASE_01("0|1", 1),
        PHASE_10("1|0", 0);

        PhaseGroup(final String description, final int altAlleleIndex) {
            this.description = description;
            this.altAlleleIndex = altAlleleIndex;
        }

        private final String description;
        private final int altAlleleIndex;

        public String getDescription() {
            return description;
        }

        public int getAltAlleleIndex() {
            return altAlleleIndex;
        }
    }

    /**
     * Returns a map with the original read as a key and the realigned read as the value.
     * <p>
     *     Missing keys or equivalent key and value pairs mean that the read was not realigned.
     * </p>
     * @return never {@code null}
     */
    public static Map<GATKRead, GATKRead> realignReadsToTheirBestHaplotype(final AlleleLikelihoods<GATKRead, Haplotype> originalReadLikelihoods, final Haplotype refHaplotype, final Locatable paddedReferenceLoc, final SmithWatermanAligner aligner, final SWParameters readToHaplotypeSWParameters) {
        final Collection<AlleleLikelihoods<GATKRead, Haplotype>.BestAllele> bestAlleles = originalReadLikelihoods.bestAllelesBreakingTies(HAPLOTYPE_ALIGNMENT_TIEBREAKING_PRIORITY);
        final Map<GATKRead, GATKRead> result = new HashMap<>(bestAlleles.size());

        for (final AlleleLikelihoods<GATKRead, Haplotype>.BestAllele bestAllele : bestAlleles) {
            final GATKRead originalRead = bestAllele.evidence;
            final Haplotype bestHaplotype = bestAllele.allele;
            final boolean isInformative = bestAllele.isInformative();
            final GATKRead realignedRead = AlignmentUtils.createReadAlignedToRef(originalRead, bestHaplotype, refHaplotype, paddedReferenceLoc.getStart(), isInformative, aligner, readToHaplotypeSWParameters);
            result.put(originalRead, realignedRead);
        }
        return result;
    }

    public static void finalizeRegion(final AssemblyRegion region,
                                      final boolean errorCorrectReads,
                                      final boolean dontUseSoftClippedBases,
                                      final byte minTailQuality,
                                      final SAMFileHeader readsHeader,
                                      final SampleList samplesList,
                                      final boolean correctOverlappingBaseQualities,
                                      final boolean softClipLowQualityEnds,
                                      final boolean overrideSoftclipFragmentCheck,
                                      final boolean trackHardclippedReads) {
        if ( region.isFinalized() ) {
            return;
        }
        final byte minTailQualityToUse = errorCorrectReads ? HaplotypeCallerEngine.MIN_TAIL_QUALITY_WITH_ERROR_CORRECTION : minTailQuality;

        final List<GATKRead> readsToUse = new ArrayList<>();
        final List<GATKRead> hardClippedReadsToUse = new ArrayList<>();

        for (final GATKRead originalRead : region.getReads()) {
            // TODO include a deletion w.r.t. the reference.  We must remove kmers that occur before the reference haplotype start
            GATKRead readTemp =  dontUseSoftClippedBases || ! ( overrideSoftclipFragmentCheck || ReadUtils.hasWellDefinedFragmentSize(originalRead)) ?
                    ReadClipper.hardClipSoftClippedBases(originalRead) : revertSoftClippedBases(originalRead);

            final GATKRead read = (softClipLowQualityEnds ? ReadClipper.softClipLowQualEnds(readTemp, minTailQualityToUse) :
                    ReadClipper.hardClipLowQualEnds(readTemp, minTailQualityToUse));

            HardClipAndPossiblyAddToCollection(region, readsToUse, originalRead, read);

            if (trackHardclippedReads) {
                final GATKRead hardClippedRead = ReadClipper.hardClipLowQualEnds(ReadClipper.hardClipSoftClippedBases(originalRead), minTailQualityToUse);

                HardClipAndPossiblyAddToCollection(region, hardClippedReadsToUse, originalRead, hardClippedRead);
            }
        }

        readsToUse.sort(new ReadCoordinateComparator(readsHeader));
        hardClippedReadsToUse.sort(new ReadCoordinateComparator(readsHeader));

        // handle overlapping read pairs from the same fragment
        if (correctOverlappingBaseQualities) {
            cleanOverlappingReadPairs(readsToUse, samplesList, readsHeader, true, OptionalInt.empty(), OptionalInt.empty());
        }

        // handle overlapping read pairs from the same fragment
        if (correctOverlappingBaseQualities) {
            cleanOverlappingReadPairs(hardClippedReadsToUse, samplesList, readsHeader, true, OptionalInt.empty(), OptionalInt.empty());
        }
        region.clearReads();
        region.addAll(readsToUse);
        region.addHardClippedPileupReads(hardClippedReadsToUse);
        region.setFinalized(true);
    }

    private static void HardClipAndPossiblyAddToCollection(final AssemblyRegion region, final List<GATKRead> readsToUse, final GATKRead originalRead, final GATKRead read) {
        if (read.getStart() <= read.getEnd() && !read.isUnmapped()) {
            final GATKRead adaptorClippedRead = ReadClipper.hardClipAdaptorSequence(read);

            if (!adaptorClippedRead.isEmpty() && adaptorClippedRead.getCigar().getReadLength() > 0) {
                final GATKRead adaptorAndRegionClippedRead = ReadClipper.hardClipToRegion(adaptorClippedRead, region.getPaddedSpan().getStart(), region.getPaddedSpan().getEnd());

                if (adaptorAndRegionClippedRead.getStart() <= adaptorAndRegionClippedRead.getEnd() && adaptorAndRegionClippedRead.getLength() > 0 && adaptorClippedRead.overlaps(region.getPaddedSpan())) {
                    // NOTE: here we make a defensive copy of the read if it has not been modified by the above operations
                    // which might only make copies in the case that the read is actually clipped
                    readsToUse.add(adaptorAndRegionClippedRead == originalRead ? adaptorAndRegionClippedRead.copy() : adaptorAndRegionClippedRead);
                }
            }
        }
    }

    /**
     *  Modify base qualities when paired reads overlap to account for the possibility of PCR error.
     *
     *  Overlapping mates provded independent evidence as far as sequencing error is concerned, but their PCR errors
     *  are correlated.  The base qualities are thus limited by the sequencing base quality as well as half of the PCR
     *  quality.  We use half of the PCR quality because downstream we treat read pairs as independent, and summing two halves
     *  effectively gives the PCR quality of the pairs when taken together.
     *
     * @param reads the list of reads to consider
     * @param samplesList   list of samples
     * @param readsHeader   bam header of reads' source
     * @param setConflictingToZero if true, set base qualities to zero when mates have different base at overlapping position
     * @param halfOfPcrSnvQual half of phred-scaled quality of substitution errors from PCR
     * @param halfOfPcrIndelQual half of phred-scaled quality of indel errors from PCR
     */
    public static void cleanOverlappingReadPairs(final List<GATKRead> reads, final SampleList samplesList, final SAMFileHeader readsHeader,
                                                 final boolean setConflictingToZero, final OptionalInt halfOfPcrSnvQual, final OptionalInt halfOfPcrIndelQual) {
        Utils.nonNull(reads);
        Utils.nonNull(samplesList);
        Utils.nonNull(halfOfPcrSnvQual);
        Utils.nonNull(halfOfPcrSnvQual);
        for ( final List<GATKRead> perSampleReadList : splitReadsBySample(samplesList, readsHeader, reads).values() ) {
            final FragmentCollection<GATKRead> fragmentCollection = FragmentCollection.create(perSampleReadList);
            for ( final Pair<GATKRead, GATKRead> overlappingPair : fragmentCollection.getOverlappingPairs() ) {
                FragmentUtils.adjustQualsOfOverlappingPairedFragments(overlappingPair, setConflictingToZero, halfOfPcrSnvQual, halfOfPcrIndelQual);
            }
        }
    }

    public static Map<String, List<GATKRead>> splitReadsBySample( final SampleList samplesList, final SAMFileHeader header, final Collection<GATKRead> reads ) {
        final Map<String, List<GATKRead>> returnMap = new HashMap<>();
        for (final String sample : samplesList.asListOfSamples()) {
            returnMap.put(sample, new ArrayList<>());
        }

        for ( final GATKRead read : reads ) {
            returnMap.get(ReadUtils.getSampleName(read, header)).add(read);
        }

        return returnMap;
    }

    /**
     * Helper function to create the reference haplotype out of the active region and a padded loc
     * @param region the active region from which to generate the reference haplotype
     * @param paddedReferenceLoc the interval which includes padding and shows how big the reference haplotype should be
     * @return a non-null haplotype
     */
    public static Haplotype createReferenceHaplotype(final AssemblyRegion region, final SimpleInterval paddedReferenceLoc, final ReferenceSequenceFile referenceReader) {
        return ReferenceConfidenceModel.createReferenceHaplotype(region, region.getAssemblyRegionReference(referenceReader), paddedReferenceLoc);
    }

    public static SimpleInterval getPaddedReferenceLoc(final AssemblyRegion region, final int referencePadding, final ReferenceSequenceFile referenceReader) {
        final int padLeft = Math.max(region.getPaddedSpan().getStart() - referencePadding, 1);
        final int padRight = Math.min(region.getPaddedSpan().getEnd() + referencePadding, referenceReader.getSequenceDictionary().getSequence(region.getPaddedSpan().getContig()).getSequenceLength());
        return new SimpleInterval(region.getPaddedSpan().getContig(), padLeft, padRight);
    }

    /**
     * Instantiates the appropriate likelihood calculation engine.
     *
     * @return never {@code null}.
     */

    public static ReadLikelihoodCalculationEngine createLikelihoodCalculationEngine(final LikelihoodEngineArgumentCollection likelihoodArgs, final boolean handleSoftClips) {
        return createLikelihoodCalculationEngine(likelihoodArgs, new FlowBasedAlignmentArgumentCollection(), handleSoftClips, likelihoodArgs.likelihoodEngineImplementation);
    }

    public static ReadLikelihoodCalculationEngine createLikelihoodCalculationEngine(final LikelihoodEngineArgumentCollection likelihoodArgs,
                                                                                    final FlowBasedAlignmentArgumentCollection fbargs,
                                                                                    final boolean handleSoftclips,
                                                                                    final ReadLikelihoodCalculationEngine.Implementation implementation) {
        //AlleleLikelihoods::normalizeLikelihoods uses Double.NEGATIVE_INFINITY as a flag to disable capping
        final double log10GlobalReadMismappingRate = getGlobalMismatchingRateFromArgs(likelihoodArgs);

        switch ( implementation) {
            // TODO these constructors should eventually be matched so they both incorporate all the same ancilliary arguments
            case PairHMM:
                return new PairHMMLikelihoodCalculationEngine((byte) likelihoodArgs.gcpHMM, likelihoodArgs.dontUseDragstrPairHMMScores ? null : DragstrParamUtils.parse(likelihoodArgs.dragstrParams),
                likelihoodArgs.pairHMMNativeArgs.getPairHMMArgs(), likelihoodArgs.pairHMM, likelihoodArgs.pairHmmResultsFile, log10GlobalReadMismappingRate, likelihoodArgs.pcrErrorModel,
                likelihoodArgs.BASE_QUALITY_SCORE_THRESHOLD, likelihoodArgs.enableDynamicReadDisqualification, likelihoodArgs.readDisqualificationThresholdConstant,
                likelihoodArgs.expectedErrorRatePerBase, !likelihoodArgs.disableSymmetricallyNormalizeAllelesToReference, likelihoodArgs.disableCapReadQualitiesToMapQ, handleSoftclips);
            case FlowBased:
                return new FlowBasedAlignmentLikelihoodEngine(fbargs, log10GlobalReadMismappingRate, likelihoodArgs.expectedErrorRatePerBase, likelihoodArgs.enableDynamicReadDisqualification, likelihoodArgs.readDisqualificationThresholdConstant);
            case FlowBasedHMM:
                return new FlowBasedHMMEngine(fbargs, (byte) likelihoodArgs.gcpHMM, log10GlobalReadMismappingRate, likelihoodArgs.expectedErrorRatePerBase, likelihoodArgs.pcrErrorModel,
                        likelihoodArgs.dontUseDragstrPairHMMScores ? null : DragstrParamUtils.parse(likelihoodArgs.dragstrParams), likelihoodArgs.enableDynamicReadDisqualification, likelihoodArgs.readDisqualificationThresholdConstant,
                        likelihoodArgs.minUsableIndelScoreToUse, (byte) likelihoodArgs.flatDeletionPenalty, (byte) likelihoodArgs.flatInsertionPenatly);
            default:
                throw new UserException("Unsupported likelihood calculation engine.");
        }
    }

    /**
     * Exposed so that PDHMM can be constructed outside of this class
     */
    public static double getGlobalMismatchingRateFromArgs(LikelihoodEngineArgumentCollection likelihoodArgs) {
        return likelihoodArgs.phredScaledGlobalReadMismappingRate < 0 ? Double.NEGATIVE_INFINITY
                : QualityUtils.qualToErrorProbLog10(likelihoodArgs.phredScaledGlobalReadMismappingRate);
    }

    public static Optional<HaplotypeBAMWriter> createBamWriter(final AssemblyBasedCallerArgumentCollection args,
                                                               final boolean createBamOutIndex,
                                                               final boolean createBamOutMD5,
                                                               final SAMFileHeader header) {
        return args.bamOutputPath != null ?
                Optional.of(new HaplotypeBAMWriter(args.bamWriterType, IOUtils.getPath(args.bamOutputPath), createBamOutIndex, createBamOutMD5, header)) :
                Optional.empty();
    }

    public static Optional<AlleleLikelihoodWriter> createAlleleLikelihoodWriter(final AssemblyBasedCallerArgumentCollection args) {
        return args.alleleLikelihoodMatrixPath != null ?
                Optional.of(new AlleleLikelihoodWriter(IOUtils.getPath(args.alleleLikelihoodMatrixPath),
                        new SimpleInterval(args.alleleLikelihoodMatrixInterval) ) ):Optional.empty();
    }



    // Contract: the List<Allele> alleles of the resulting VariantContext is the ref allele followed by alt alleles in the
    // same order as in the input vcs
    public static VariantContext makeMergedVariantContext(final List<VariantContext> vcs) {
        if (vcs.isEmpty()) {
            return null;
        }
        final List<String> haplotypeSources = vcs.stream().map(VariantContext::getSource).collect(Collectors.toList());
        return GATKVariantContextUtils.simpleMerge(vcs, haplotypeSources,
                GATKVariantContextUtils.FilteredRecordMergeType.KEEP_IF_ANY_UNFILTERED,
                GATKVariantContextUtils.GenotypeMergeType.PRIORITIZE, false);
    }


    /**
     * High-level function that runs the assembler on the given region's reads,
     * returning a data structure with the resulting information needed
     * for further HC steps
     */
    public static AssemblyResultSet assembleReads(final AssemblyRegion region,
                                                  final AssemblyBasedCallerArgumentCollection argumentCollection,
                                                  final SAMFileHeader header,
                                                  final SampleList sampleList,
                                                  final Logger logger,
                                                  final ReferenceSequenceFile referenceReader,
                                                  final ReadThreadingAssembler assemblyEngine,
                                                  final SmithWatermanAligner aligner,
                                                  final boolean correctOverlappingBaseQualities,
                                                  final FlowBasedArgumentCollection fbargs,
                                                  final boolean bypassAssembly){
        finalizeRegion(region,
                argumentCollection.assemblerArgs.errorCorrectReads,
                argumentCollection.dontUseSoftClippedBases,
                (byte)(argumentCollection.minBaseQualityScore - 1),
                header,
                sampleList,
                correctOverlappingBaseQualities,
                argumentCollection.softClipLowQualityEnds,
                argumentCollection.overrideSoftclipFragmentCheck,
                true);


        if( argumentCollection.assemblerArgs.debugAssembly) {
            logger.info("Assembling " + region.getSpan() + " with " + region.size() + " reads:    (with overlap region = " + region.getPaddedSpan() + ")");
        }

        final byte[] fullReferenceWithPadding = region.getAssemblyRegionReference(referenceReader, REFERENCE_PADDING_FOR_ASSEMBLY);
        final SimpleInterval paddedReferenceLoc = getPaddedReferenceLoc(region, REFERENCE_PADDING_FOR_ASSEMBLY, referenceReader);
        final Haplotype refHaplotype = createReferenceHaplotype(region, paddedReferenceLoc, referenceReader);

        final ReadErrorCorrector readErrorCorrector = argumentCollection.assemblerArgs.pileupErrorCorrectionLogOdds == Double.NEGATIVE_INFINITY ?
                (argumentCollection.assemblerArgs.errorCorrectReads ?
                        new NearbyKmerErrorCorrector(argumentCollection.assemblerArgs.kmerLengthForReadErrorCorrection,
                                HaplotypeCallerEngine.MIN_TAIL_QUALITY_WITH_ERROR_CORRECTION,
                                argumentCollection.assemblerArgs.minObservationsForKmerToBeSolid,
                                argumentCollection.assemblerArgs.debugAssembly,
                                fullReferenceWithPadding) :
                        null)
                : new PileupReadErrorCorrector(argumentCollection.assemblerArgs.pileupErrorCorrectionLogOdds, header);
        final SWParameters danglingEndSWParameters = argumentCollection.getDanglingEndSWParameters();
        final SWParameters haplotypeToReferenceSWParameters = argumentCollection.getHaplotypeToReferenceSWParameters();

        // establish reference mapper, if needed
        int collapseHmerSize = argumentCollection.flowAssemblyCollapseHKerSize;
        if (collapseHmerSize == DETERMINE_COLLAPSE_THRESHOLD){
            collapseHmerSize = AssemblyBasedCallerUtils.determineFlowAssemblyColapseHmer(header);
        }
        final LongHomopolymerHaplotypeCollapsingEngine haplotypeCollapsing = ( collapseHmerSize > 0 && LongHomopolymerHaplotypeCollapsingEngine.needsCollapsing(refHaplotype.getBases(), collapseHmerSize, logger))
                                            ? new LongHomopolymerHaplotypeCollapsingEngine(collapseHmerSize, argumentCollection.flowAssemblyCollapsePartialMode, fullReferenceWithPadding,
                paddedReferenceLoc, logger, argumentCollection.assemblerArgs.debugAssembly, aligner, argumentCollection.getHaplotypeToReferenceSWParameters())
                                            : null;
        if ( haplotypeCollapsing != null ) {
            logger.debug("deploying haplotypeCollapsing on " + paddedReferenceLoc + ", region: " + region);
        }

        try {
            final AssemblyResultSet assemblyResultSet =
                    !bypassAssembly
                            ? assemblyEngine.runLocalAssembly(
                            region,
                            refHaplotype,
                            fullReferenceWithPadding,
                            paddedReferenceLoc,
                            readErrorCorrector,
                            header, aligner,
                            haplotypeCollapsing,
                            danglingEndSWParameters, haplotypeToReferenceSWParameters)
                            : assemblyEngine.generateEmptyLLocalAssemblyResult(
                            region,
                            refHaplotype,
                            fullReferenceWithPadding,
                            paddedReferenceLoc,
                            haplotypeCollapsing);

            assemblyResultSet.setHaplotypeCollapsingEngine(haplotypeCollapsing);
            assemblyResultSet.setDebug(argumentCollection.assemblerArgs.debugAssembly);
            assemblyResultSet.debugDump(logger);
            return assemblyResultSet;
        } catch (final Exception e){
            // Capture any exception that might be thrown, and write out the assembly failure BAM if requested
            if (argumentCollection.assemblerArgs.captureAssemblyFailureBAM){
                try (final SAMFileWriter writer = ReadUtils.createCommonSAMWriter(new File("assemblyFailure.bam"), null, header, false, false, false)){
                    for (final GATKRead read : region.getReads()) {
                        writer.addAlignment(read.convertToSAMRecord(header));
                    }
                }
            }
            throw e;
        }
    }

    private static int determineFlowAssemblyColapseHmer(SAMFileHeader readsHeader) {
        int result = 0;
        List<SAMReadGroupRecord> rgr = readsHeader.getReadGroups();
        for (SAMReadGroupRecord rg : rgr) {
            FlowBasedReadUtils.ReadGroupInfo rgi = new FlowBasedReadUtils.ReadGroupInfo(rg);
            if (rgi.maxClass >= result) {
                result = rgi.maxClass;
            }
        }
        return result;
    }

    /**
     * Returns a map of kmer -> count of total unique occurrences across all of the provided reads. This is a necessary step
     * in the {@link AssemblyResultSet#injectPileupEvents} pileup haplotype filtering.
     *
     * @param hardClippedPileupReads  Reads to scan to genreate kmer counts from
     * @param kmerSize                kmer size to use in kmerizing the reads
     * @return a map of kmer to the number of occurences in the data.
     */
    static Map<Kmer, MutableInt>  getKmerReadCounts(final List<GATKRead> hardClippedPileupReads, int kmerSize) {
        Map<Kmer, MutableInt> kmerReadCounts = new HashMap<>();
        for (final GATKRead read : hardClippedPileupReads) {
            final byte[] bases = read.getBasesNoCopy();
            new IndexRange(0, Math.max(bases.length - kmerSize + 1, 0))
                    .forEach(i -> kmerReadCounts.computeIfAbsent(new Kmer(bases, i, kmerSize), k -> new MutableInt(0)).increment());
        }
        return kmerReadCounts;
    }

    /**
     * Method for filtering combinatorial expansion of the number of extra artifical haplotypes that have been created to
     * a more tractable number. It works in the following way:
     *    - For each kmer in the kmerReadsCounts, find haplotypes that also contain that kmer and increment a score for each
     *      found haplotype by the support found for the kmer.
     *    - Once this is done select the top #kmerSize haplotypes from the list as the most probable.
     *
     * @return A set of artificial haplotypes limited to at most numPileupHaplotypes
     */
    @VisibleForTesting
    static Set<Haplotype> filterPileupHaplotypes(final Set<Haplotype> onlyNewHaplotypes,
                                                 final Map<Kmer, MutableInt> kmerReadCounts,
                                                 final int numPileupHaplotypes,
                                                 final int kmerSize ) {
        if (onlyNewHaplotypes.size() <= numPileupHaplotypes) {
            return onlyNewHaplotypes;   // if the limiting haplotype count is more haplotypes than we have, everything passes
        }

        // sort by score = # of kmers in haplotype that appear in any read
        // TODO this code might use some normalizing based on haplotype length in the future
        final Map<Haplotype, Long> scores =  onlyNewHaplotypes.stream()
                .collect(Collectors.toMap(h -> h, h -> kmerizeSequence(h.getBases(), kmerSize).stream()
                                .filter(kmer -> kmerReadCounts.getOrDefault(kmer, new MutableInt(0)).intValue() > 0)
                                .count()));

        // if there are ties, we pass any haplotype with a score as good as the numPileupHaplotypes-th best
        final long minimumPassingScore = scores.values().stream().sorted(Comparator.reverseOrder()).skip(numPileupHaplotypes - 1).findFirst().get();
        return onlyNewHaplotypes.stream().filter(h -> scores.get(h) >= minimumPassingScore).collect(Collectors.toSet());
    }


    private static Set<Kmer> kmerizeSequence(byte[] sequence, int kmerSize){
        final Set<Kmer> allKmers = new LinkedHashSet<>();
        final int stopPosition = sequence.length - kmerSize;
        for (int i = 0; i <= stopPosition; i++) {
            final Kmer kmer = new Kmer(sequence, i, kmerSize);
            allKmers.add(kmer);
        }
        return allKmers;
    }

    /**
     * Annotates reads in AlleleLikelihoods with alignment region (the ref region spanned by the haplotype the read is aligned to) and
     * callable region (the ref region over which a caller is using these AlleleLikelihoods to call variants)
     *
     * @param likelihoods AlleleLikelihoods containing reads to be annotated along with haplotypes to which these reads have been aligned
     * @param callableRegion ref region over which caller is using these AlleleLikelihoods to call variants
     */
    public static void annotateReadLikelihoodsWithRegions(final AlleleLikelihoods<GATKRead, Haplotype> likelihoods,
                                                          final Locatable callableRegion) {
        //assign alignment regions to each read
        final Collection<AlleleLikelihoods<GATKRead, Haplotype>.BestAllele> bestHaplotypes = likelihoods.bestAllelesBreakingTies(HAPLOTYPE_ALIGNMENT_TIEBREAKING_PRIORITY);
        bestHaplotypes.forEach(bh -> bh.evidence.setAttribute(ALIGNMENT_REGION_TAG, bh.allele.getGenomeLocation().toString()));
        for (final AlleleLikelihoods<GATKRead, Haplotype>.BestAllele bestHaplotype : bestHaplotypes) {
            final GATKRead read = bestHaplotype.evidence;
            final Haplotype haplotype = bestHaplotype.allele;
            read.setAttribute(ALIGNMENT_REGION_TAG, haplotype.getGenomeLocation().toString());
        }

        //assign callable region to each read
        final int sampleCount = likelihoods.numberOfSamples();
        for (int i = 0; i < sampleCount; i++) {
            for (final GATKRead read : likelihoods.sampleEvidence(i)) {
                read.setAttribute(CALLABLE_REGION_TAG, callableRegion.toString());
            }
        }
    }

    /**
     * For the given variant, reads are annotated with which alleles they support, if any.  If a read already has a
     * supported alleles annotation this additional annotation is appended to the previous annotation, it does not replace it.
     * @param vc The variant for which to annotate the reads
     * @param likelihoodsAllele ReadLiklihoods containing reads to be annotated along with alleles of the variant vc
     */
    public static void annotateReadLikelihoodsWithSupportedAlleles(final VariantContext vc,
                                                                     final AlleleLikelihoods<GATKRead, Allele> likelihoodsAllele) {
        annotateReadLikelihoodsWithSupportedAlleles(vc, likelihoodsAllele, Collections::singletonList);
    }

    /**
     * For the given variant, reads are annotated with which alleles they support, if any.  If a read already has a
     * supported alleles annotation this additional annotation is appended to the previous annotation, it does not replace it.
     * @param vc The variant for which to annotate the reads
     * @param likelihoodsAllele ReadLiklihoods containing reads to be annotated along with alleles of the variant vc
     */
    public static <U extends Locatable> void annotateReadLikelihoodsWithSupportedAlleles(final VariantContext vc, final AlleleLikelihoods<U, Allele> likelihoodsAllele,
                                                                       final Function<U, Collection<GATKRead>> readCollectionFunc) {
        //assign supported alleles to each read
        final Map<Allele, List<Allele>> alleleSubset = vc.getAlleles().stream().collect(Collectors.toMap(a -> a, Arrays::asList));
        final AlleleLikelihoods<U, Allele> subsettedLikelihoods = likelihoodsAllele.marginalize(alleleSubset);
        final Collection<AlleleLikelihoods<U, Allele>.BestAllele> bestAlleles = subsettedLikelihoods.bestAllelesBreakingTies().stream()
                .filter(ba -> ba.isInformative()).collect(Collectors.toList());
        for (AlleleLikelihoods<U, Allele>.BestAllele bestAllele : bestAlleles) {
            final Allele allele = bestAllele.allele;
            for (final GATKRead read : readCollectionFunc.apply(bestAllele.evidence)) {
                final String prevAllelesString = read.hasAttribute(SUPPORTED_ALLELES_TAG) ? read.getAttributeAsString(SUPPORTED_ALLELES_TAG) + ", " : "";
                final String newAllelesString = vc.getContig() + ":" + vc.getStart() + "=" + vc.getAlleleIndex(allele);
                read.setAttribute(SUPPORTED_ALLELES_TAG, prevAllelesString + newAllelesString);
            }
        }
    }

    /*
     * Create a context that maps each read to the reference haplotype with log10 L of 0
     * @param refHaplotype a non-null reference haplotype
     * @param samples a list of all samples
     * @param readsHeader SAM header to use for querying sample name from read
     * @param region the assembly region containing reads
     * @return a placeholder AlleleLikelihoods data structure with likelihoods all set to zero
     */
    public static AlleleLikelihoods<GATKRead, Haplotype> createDummyStratifiedReadMap(final Haplotype refHaplotype,
                                                                          final SampleList samples,
                                                                          final SAMFileHeader readsHeader,
                                                                          final AssemblyRegion region) {
        return new AlleleLikelihoods<>(samples, new IndexedAlleleList<>(refHaplotype),
                splitReadsBySample(samples, readsHeader, region.getReads()));
    }

    /**
     * Get a list of pileups that span the entire active region span, in order, one for each position
     */
    public static List<ReadPileup> getPileupsOverReference(final SAMFileHeader readsHeader,
                                                     final SimpleInterval activeRegionSpan,
                                                     final AlleleLikelihoods<GATKRead, Haplotype> readLikelihoods,
                                                     final SampleList samples) {
        final List<GATKRead> reads = new ArrayList<>(readLikelihoods.sampleEvidence(0));
        reads.sort(new ReadCoordinateComparator(readsHeader));  //because we updated the reads based on the local realignments we have to re-sort or the pileups will be... unpredictable

        final LocusIteratorByState libs = new LocusIteratorByState(reads.iterator(), LocusIteratorByState.NO_DOWNSAMPLING, samples.asSetOfSamples(), readsHeader, true);

        final int startPos = activeRegionSpan.getStart();
        final List<ReadPileup> pileups = new ArrayList<>(activeRegionSpan.getEnd() - startPos);
        AlignmentContext next = libs.advanceToLocus(startPos, true);
        for ( int curPos = startPos; curPos <= activeRegionSpan.getEnd(); curPos++ ) {
            if ( next != null && next.getLocation().getStart() == curPos ) {
                pileups.add(next.getBasePileup());
                next = libs.hasNext() ? libs.next() : null;
            } else {
                // no data, so we create empty pileups
                pileups.add(new ReadPileup(new SimpleInterval(activeRegionSpan.getContig(), curPos, curPos)));
            }
        }

        return pileups;
    }


    /**
     * Returns the list of variants discovered in assembled haplotypes that are active at this location. The results will
     * include events that span the current location if includeSpanningEvents is set to true; otherwise it will only
     * include events that have loc as their start position.
     *
     * This marks the point in the code at which Events discovered from assembly are converted to VariantContext object.
     * Prior to this point everything is an Event -- that is, biallelic ref -> alt substitutions -- and after this point
     * everything is contained in heavier, potentially multiallelic, VariantContext objects for genotyping and output.
     * @param loc The start position we are genotyping
     * @param haplotypes list of active haplotypes at the current location
     * @param includeSpanningEvents If true, will also return events that span loc
     */
    public static List<VariantContext> getVariantsFromActiveHaplotypes(final int loc, final List<Haplotype> haplotypes, final boolean includeSpanningEvents) {
        final Set<Event> events = new HashSet<>();
        final List<VariantContext> result = new ArrayList<>();

        int hapNumber = 0;
        for (final Haplotype haplotype : haplotypes) {
            final String sourceName = "HC" + hapNumber++;
            for (final Event event : haplotype.getEventMap().getOverlappingEvents(loc)) {
                if (event == null || (!includeSpanningEvents && event.getStart() != loc)) {
                    continue;
                } else if (events.add(event)) {
                    result.add(event.convertToVariantContext(sourceName));
                }
            }
        }

        return result;
    }

    /**
     * Returns a mapping from Allele in the mergedVC, which represents all of the alleles being genotyped at loc,
     * to a list of Haplotypes that support that allele. If the mergedVC includes a spanning deletion allele, all haplotypes
     * that support spanning deletions will be assigned to that allele in the map.
     *
     * @param mergedVC The merged variant context for the locus, which includes all active alternate alleles merged to a single reference allele
     * @param loc The active locus being genotyped
     * @param haplotypes Haplotypes for the current active region
     * @param emitSpanningDels If true will map spanning events to a * allele instead of reference // TODO add a test for this behavior
     * @return
     */
    public static Map<Allele, List<Haplotype>> createAlleleMapper(final VariantContext mergedVC, final int loc, final List<Haplotype> haplotypes, final boolean emitSpanningDels) {

        final Map<Allele, List<Haplotype>> result = new LinkedHashMap<>();

        final Allele ref = mergedVC.getReference();
        result.put(ref, new ArrayList<>());

        //Note: we can't use the alleles implied by eventsAtThisLoc because they are not yet merged to a common reference
        //For example, a homopolymer AAAAA reference with a single and double deletion would yield (i) AA* A and (ii) AAA* A
        //in eventsAtThisLoc, when in mergedVC it would yield AAA* AA A
        mergedVC.getAlternateAlleles().stream().filter(a -> !a.isSymbolic()).forEach(a -> result.put(a, new ArrayList<>()));

        for (final Haplotype h : haplotypes) {

            // Partially determined haplotypes know at what position they are determined, only determined position haps should be considered for genotyping
            if (h.isPartiallyDetermined() && ((PartiallyDeterminedHaplotype) h).getDeterminedPosition() != loc) {
                continue;
            }
            final List<Event> overlappingEvents = h.getEventMap().getOverlappingEvents(loc);

            if (overlappingEvents.isEmpty()) {    //no events --> this haplotype supports the reference at this locus
                result.get(ref).add(h);
                continue;
            }

            for (Event overlappingEvent : overlappingEvents) {
                if (overlappingEvent.getStart() == loc) {
                    // the event starts at the current location

                    if (overlappingEvent.refAllele().length() == mergedVC.getReference().length()) {
                        // reference allele lengths are equal; we can just use the spanning event's alt allele
                        // in the case of GGA mode the spanning event might not match an allele in the mergedVC
                        if (result.containsKey(overlappingEvent.altAllele())) {
                            // variant contexts derived from the event map have only one alt allele each, so we can just
                            // grab the first one (we're not assuming that the sample is biallelic)
                            result.get(overlappingEvent.altAllele()).add(h);
                        }
                    } else if (overlappingEvent.refAllele().length() < mergedVC.getReference().length()) {
                        // spanning event has shorter ref allele than merged VC; we need to pad out its alt allele
                        final Map<Allele, Allele> spanningEventAlleleMappingToMergedVc
                                = GATKVariantContextUtils.createAlleleMapping(mergedVC.getReference(), overlappingEvent.refAllele(), Collections.singletonList(overlappingEvent.altAllele()));
                        final Allele remappedSpanningEventAltAllele = spanningEventAlleleMappingToMergedVc.get(overlappingEvent.altAllele());
                        // in the case of GGA mode the spanning event might not match an allele in the mergedVC
                        if (result.containsKey(remappedSpanningEventAltAllele)) {
                            result.get(remappedSpanningEventAltAllele).add(h);
                        }
                    } else {
                        // the process of creating the merged VC in AssemblyBasedCallerUtils::makeMergedVariantContext should have
                        // already padded out the reference allele, therefore this spanning VC must not be in events at this site
                        // because we're in GGA mode and it's not an allele we want
                        continue;
                    }
                } else {
                    if (emitSpanningDels) {
                        // the event starts prior to the current location, so it's a spanning deletion
                        if (!result.containsKey(Allele.SPAN_DEL)) {
                            result.put(Allele.SPAN_DEL, new ArrayList<>());
                        }
                        result.get(Allele.SPAN_DEL).add(h);
                        // there might be a del+ins at the site in question and this would miss one of them unless its a continue
                        break; //Why is there a break here? Shouldn't this be a continue? Why should the first spanning event overlap?A
                    } else {
                        result.get(ref).add(h);
                        break;
                    }
                }

            }

        }
        return result;
    }


    /**
     * Tries to phase the individual alleles based on pairwise comparisons to the other alleles based on all called haplotypes
     *
     * @param calls             the list of called alleles
     * @param calledHaplotypes  the set of haplotypes used for calling
     * @return a non-null list which represents the possibly phased version of the calls
     */
    public static List<VariantContext> phaseCalls(final List<VariantContext> calls, final Set<Haplotype> calledHaplotypes) {

        // construct a mapping from alternate allele to the set of haplotypes that contain that allele
        final Map<VariantContext, Set<Haplotype>> haplotypeMap = constructHaplotypeMapping(calls, calledHaplotypes);

        // construct a mapping from call to phase set ID
        final Map<VariantContext, Pair<Integer, PhaseGroup>> phaseSetMapping =
                constructPhaseSetMapping(calls, haplotypeMap);
        final int uniqueCounterEndValue = Math.toIntExact(phaseSetMapping.values().stream().map(Pair::getLeft).distinct().count());

        // we want to establish (potential) *groups* of phased variants, so we need to be smart when looking at pairwise phasing partners
        return constructPhaseGroups(calls, phaseSetMapping, uniqueCounterEndValue);
    }

    /**
     * Construct the mapping from alternate allele to the set of haplotypes that contain that allele
     *
     * @param originalCalls    the original unphased calls
     * @param calledHaplotypes  the set of haplotypes used for calling
     * @return non-null Map
     */
    @VisibleForTesting
    static Map<VariantContext, Set<Haplotype>> constructHaplotypeMapping(final List<VariantContext> originalCalls,
                                                                                   final Set<Haplotype> calledHaplotypes) {
        final Map<VariantContext, Set<Haplotype>> haplotypeMap = new HashMap<>(originalCalls.size());
        for ( final VariantContext call : originalCalls ) {
            // don't try to phase if there is not exactly 1 alternate allele
            if ( ! isBiallelicWithOneSiteSpecificAlternateAllele(call) ) {
                haplotypeMap.put(call, Collections.emptySet());
                continue;
            }

            // keep track of the haplotypes that contain this particular alternate allele
            final Allele alt = getSiteSpecificAlternateAllele(call);
            final Predicate<Event> hasThisAlt = vc -> (vc.getStart() == call.getStart() && vc.altAllele().equals(alt));
            final Set<Haplotype> hapsWithAllele = calledHaplotypes.stream()
                    .filter(h -> h.getEventMap().getEvents().stream().anyMatch(hasThisAlt))
                    .collect(Collectors.toCollection(HashSet<Haplotype>::new));

            haplotypeMap.put(call, hapsWithAllele);
        }

        return haplotypeMap;
    }

    /**
     * If at least one exists, returns a concrete (not NONREF) site-specific (starting at the current POS) alternate allele
     * from within the current variant context.
     */
    private static Allele getSiteSpecificAlternateAllele(final VariantContext call) {
        return call.getAlternateAlleles().stream().filter(AssemblyBasedCallerUtils::isSiteSpecificAltAllele).findFirst().orElse(null);
    }

    /**
     * Construct the mapping from call (variant context) to phase set ID
     *
     * @param originalCalls    the original unphased calls
     * @param haplotypeMap     mapping from alternate allele to the set of haplotypes that contain that allele
     * @return a map from each variant context to a pair with the phase set ID and phase group of the alt allele
     *  note this may be empty in impossible-to-phase situations
     */
    @VisibleForTesting
    static Map<VariantContext, Pair<Integer, PhaseGroup>> constructPhaseSetMapping(final List<VariantContext> originalCalls,
                                                                                   final Map<VariantContext, Set<Haplotype>> haplotypeMap) {

        // count the total number of alternate haplotypes
        final Set<Haplotype> haplotypesWithCalledVariants = new HashSet<>();
        haplotypeMap.values().forEach(haplotypesWithCalledVariants::addAll);
        final int totalAvailableHaplotypes = haplotypesWithCalledVariants.size();

        final Map<VariantContext, Pair<Integer, PhaseGroup>> phaseSetMapping = new HashMap<>(haplotypeMap.size());

        final int numCalls = originalCalls.size();
        int uniqueCounter = 0;

        // use the haplotype mapping to connect variants that are always/never present on the same haplotypes
        for ( int i = 0; i < numCalls - 1; i++ ) {
            final VariantContext call = originalCalls.get(i);
            final Set<Haplotype> haplotypesWithCall = haplotypeMap.get(call);
            if ( haplotypesWithCall.isEmpty() ) {
                continue;
            }

            // NB: callIsOnAllAltHaps does not necessarily mean a homozygous genotype, since this method does
            // not consider the reference haplotype
            final boolean callIsOnAllAltHaps = haplotypesWithCall.size() == totalAvailableHaplotypes;

            // if the call is on all haplotypes but we only use some of them to phase it with another variant
            // we need to keep track of which ones are still active for downstream phasing.
            // ie if the call is on all alt haps but we phase it with a site that is only on one of the alt haps,
            // we remove the haplotypes with ref at the comp site for the purposes of phasing with additional variants
            // downstream. This set keeps track of what call haplotypes are available for phasing downstream for "callIsOnAllAltHaps" variants.
            final Set<Haplotype> callHaplotypesAvailableForPhasing = new HashSet<>(haplotypesWithCall);

            for ( int j = i+1; j < numCalls; j++ ) {
                final VariantContext comp = originalCalls.get(j);
                final Set<Haplotype> haplotypesWithComp = haplotypeMap.get(comp);
                if ( haplotypesWithComp.isEmpty() ) {
                    continue;
                }

                // if the variants are together on all alt haplotypes, record that fact. NB that this does not mean that the
                // genotype for the variant is homozygous since this method does not consider the ref haplotype
                final boolean compIsOnAllAltHaps = haplotypesWithComp.size() == totalAvailableHaplotypes;

                if ( (haplotypesWithCall.size() == haplotypesWithComp.size() && haplotypesWithCall.containsAll(haplotypesWithComp))
                        || (callIsOnAllAltHaps &&  callHaplotypesAvailableForPhasing.containsAll(haplotypesWithComp))
                        || compIsOnAllAltHaps ) {

                    // create a new group if these are the first entries
                    if ( ! phaseSetMapping.containsKey(call) ) {
                        // note that if the comp is already in the map then that is very bad because it means that there is
                        // another variant that is in phase with the comp but not with the call.  Since that's an un-phasable
                        // situation, we should abort if we encounter it.
                        if ( phaseSetMapping.containsKey(comp) ) {
                            phaseSetMapping.clear();
                            return phaseSetMapping;
                        }

                        // An important note: even for homozygous variants we are setting the phase as "0|1" here. We
                        // don't know at this point whether the genotype is homozygous vs the variant being on all alt
                        // haplotypes, for one thing. Also, we cannot possibly know for sure at this time that the genotype for this
                        // sample will actually be homozygous downstream: there are steps in the pipeline that are liable
                        // to change the genotypes.  Because we can't make those assumptions here, we have decided to output
                        // the phase as if the call is heterozygous and then "fix" it downstream as needed.
                        phaseSetMapping.put(call, Pair.of(uniqueCounter, PhaseGroup.PHASE_01));
                        phaseSetMapping.put(comp, Pair.of(uniqueCounter, PhaseGroup.PHASE_01));

                        // if the call was on all alternate haps but the comp isn't, we need to narrow down the set of
                        // alt haps we'll consider for further phasing other variants with the call
                        callHaplotypesAvailableForPhasing.retainAll(haplotypesWithComp);

                        uniqueCounter++;
                    }
                    // otherwise it's part of an existing group so use that group's unique ID
                    else if ( ! phaseSetMapping.containsKey(comp) ) {
                        final Pair<Integer, PhaseGroup> callPhase = phaseSetMapping.get(call);
                        phaseSetMapping.put(comp, Pair.of(callPhase.getLeft(), callPhase.getRight()));
                    }
                }
                // if the variants are apart on *all* alternate haplotypes, record that fact
                else if ( haplotypesWithCall.size() + haplotypesWithComp.size() == totalAvailableHaplotypes ) {

                    final Set<Haplotype> intersection = new HashSet<>();
                    intersection.addAll(haplotypesWithCall);
                    intersection.retainAll(haplotypesWithComp);
                    if ( intersection.isEmpty() ) {
                        // create a new group if these are the first entries
                        if ( ! phaseSetMapping.containsKey(call) ) {
                            // note that if the comp is already in the map then that is very bad because it means that there is
                            // another variant that is in phase with the comp but not with the call.  Since that's an un-phasable
                            // situation, we should abort if we encounter it.
                            if ( phaseSetMapping.containsKey(comp) ) {
                                phaseSetMapping.clear();
                                return phaseSetMapping;
                            }

                            phaseSetMapping.put(call, Pair.of(uniqueCounter, PhaseGroup.PHASE_01));
                            phaseSetMapping.put(comp, Pair.of(uniqueCounter, PhaseGroup.PHASE_10));
                            uniqueCounter++;
                        }
                        // otherwise it's part of an existing group so use that group's unique ID
                        else if ( ! phaseSetMapping.containsKey(comp) ){
                            final Pair<Integer, PhaseGroup> callPhase = phaseSetMapping.get(call);
                            phaseSetMapping.put(comp, Pair.of(callPhase.getLeft(), callPhase.getRight().equals(PhaseGroup.PHASE_01) ? PhaseGroup.PHASE_10 : PhaseGroup.PHASE_01));
                        }
                    }
                }
            }
        }

        return phaseSetMapping;
    }

    /**
     * Assemble the phase groups together and update the original calls accordingly
     *
     * @param originalCalls    the original unphased calls
     * @param phaseSetMapping  mapping from call (variant context) to phase group ID
     * @param indexTo          last index (exclusive) of phase group IDs
     * @return a non-null list which represents the possibly phased version of the calls
     */
    @VisibleForTesting
    static List<VariantContext> constructPhaseGroups(final List<VariantContext> originalCalls,
                                                               final Map<VariantContext, Pair<Integer, PhaseGroup>> phaseSetMapping,
                                                               final int indexTo) {
        final List<VariantContext> phasedCalls = new ArrayList<>(originalCalls);

        // if we managed to find any phased groups, update the VariantContexts
        for ( int count = 0; count < indexTo; count++ ) {
            // get all of the (indexes of the) calls that belong in this group (keeping them in the original order)
            final List<Integer> indexes = new ArrayList<>();
            for ( int index = 0; index < originalCalls.size(); index++ ) {
                final VariantContext call = originalCalls.get(index);
                if ( phaseSetMapping.containsKey(call) && phaseSetMapping.get(call).getLeft() == count ) {
                    indexes.add(index);
                }
            }
            if ( indexes.size() < 2 ) {
                throw new IllegalStateException("Somehow we have a group of phased variants that has fewer than 2 members");
            }

            // create a unique ID based on the leftmost one
            final String uniqueID = createUniqueID(originalCalls.get(indexes.get(0)));

            // create the phase set identifier, which is the position of the first variant in the set
            final int phaseSetID = originalCalls.get(indexes.get(0)).getStart();

            // update the VCs
            for ( final int index : indexes ) {
                final VariantContext originalCall = originalCalls.get(index);
                final VariantContext phasedCall = phaseVC(originalCall, uniqueID, phaseSetMapping.get(originalCall).getRight(), phaseSetID);
                phasedCalls.set(index, phasedCall);
            }
        }

        return phasedCalls;
    }

    /**
     * Is this variant bi-allelic?  This implementation is very much specific to this class so shouldn't be pulled out into a generalized place.
     *
     * @param vc the variant context
     * @return true if this variant context is bi-allelic, ignoring the NON-REF symbolic allele and '*' symbolic allele, false otherwise
     */
    private static boolean isBiallelicWithOneSiteSpecificAlternateAllele(final VariantContext vc) {
        return vc.getAlternateAlleles().stream().filter(AssemblyBasedCallerUtils::isSiteSpecificAltAllele).count() == 1;
    }

    /**
     * A site-specific alternate allele is one that represents concrete (i.e. not NONREF) variation that begins at the
     * site (i.e. not '*', which represents a concrete alternate allele that begins upstream of the current site).
     */
    private static boolean isSiteSpecificAltAllele(final Allele a) {
        return !(a.isReference() || a.isNonRefAllele() || Allele.SPAN_DEL.equals(a));
    }

    /**
     * Create a unique identifier given the variant context
     *
     * @param vc   the variant context
     * @return non-null String
     */
    private static String createUniqueID(final VariantContext vc) {
        return String.format("%d_%s_%s", vc.getStart(), vc.getReference().getDisplayString(), vc.getAlternateAllele(0).getDisplayString());
    }

    /**
     * Add physical phase information to the provided variant context
     *
     * @param vc   the variant context
     * @param ID   the ID to use
     * @param phaseGT the phase GT string to use
     * @return phased non-null variant context
     */
    private static VariantContext phaseVC(final VariantContext vc, final String ID, final PhaseGroup phaseGT, final int phaseSetID) {
        final List<Genotype> phasedGenotypes = new ArrayList<>();
        for ( final Genotype g : vc.getGenotypes() ) {
            final List<Allele> alleles = g.getAlleles();
            final List<Allele> newAlleles = new ArrayList<>(alleles);
            final int phasedAltAlleleIndex = phaseGT.getAltAlleleIndex();
            if (g.isHet() && ! isSiteSpecificAltAllele(newAlleles.get(phasedAltAlleleIndex))) {
                Collections.reverse(newAlleles);
            }
            final Genotype genotype = new GenotypeBuilder(g)
                .alleles(newAlleles)
                .phased(true)
                .attribute(GATKVCFConstants.HAPLOTYPE_CALLER_PHASING_ID_KEY, ID)
                .attribute(GATKVCFConstants.HAPLOTYPE_CALLER_PHASING_GT_KEY, phaseGT.getDescription())
                .attribute(VCFConstants.PHASE_SET_KEY, phaseSetID)
                .make();
            phasedGenotypes.add(genotype);
        }
        return new VariantContextBuilder(vc).genotypes(phasedGenotypes).make();
    }

    // find all alleles in a VariantContext that encode an equivalent ref -> alt as at least one given event
    public static Set<Allele> allelesConsistentWithGivenAlleles(final Collection<Event> givenAlleles, final VariantContext mergedVC) {
        return givenAlleles.isEmpty() ? Collections.emptySet() : mergedVC.getAlternateAlleles().stream()
                .filter(allele -> givenAlleles.stream().anyMatch(ga -> allelesAreConsistent(ga.refAllele(), ga.altAllele(), mergedVC.getReference(), allele)))
                .collect(Collectors.toSet());
    }

    // check whether two alleles coming from different variant contexts and with possibly different reference alleles
    // could in fact be the same.  The condition is that one is a prefix of the other
    private static boolean allelesAreConsistent(final Allele ref1, final Allele alt1, final Allele ref2, final Allele alt2) {
        if (alt1.isSymbolic() || alt2.isSymbolic()) {
            return false;
        } else {
            final int sizeDiff1 = alt1.length() - ref1.length();
            final int sizeDiff2 = alt2.length() - ref2.length();
            return (sizeDiff1 == sizeDiff2) && (alt1.length() < alt2.length() ?
                    alt1.basesMatch(Arrays.copyOf(alt2.getBases(), alt1.length())) :
                    alt2.basesMatch(Arrays.copyOf(alt1.getBases(), alt2.length())));
        }
    }

    // revert soft clipped bases, but also save the original position in a tag
    private static GATKRead revertSoftClippedBases(GATKRead inputRead){
        int softStart = inputRead.getStart();
        int softEnd = inputRead.getEnd();
        GATKRead result = ReadClipper.revertSoftClippedBases(inputRead);
        result.setAttribute(ReferenceConfidenceModel.ORIGINAL_SOFTCLIP_START_TAG, softStart);
        result.setAttribute(ReferenceConfidenceModel.ORIGINAL_SOFTCLIP_END_TAG, softEnd);
        return result;
    }

}
