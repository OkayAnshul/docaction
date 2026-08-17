package com.okayanshul.docaction.imports

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.okayanshul.docaction.actions.calendar.CalendarEventExecutor
import com.okayanshul.docaction.actions.calendar.CalendarTarget
import com.okayanshul.docaction.actions.calendar.CalendarTargets
import com.okayanshul.docaction.actions.reminder.NotificationPublisher
import com.okayanshul.docaction.actions.reminder.ReminderPlanner
import com.okayanshul.docaction.actions.reminder.ReminderScheduler
import com.okayanshul.docaction.core.database.Databases
import com.okayanshul.docaction.core.designsystem.StageLine
import com.okayanshul.docaction.core.designsystem.StageState
import com.okayanshul.docaction.core.settings.ReminderPreferences
import com.okayanshul.docaction.core.settings.ReminderSettings
import com.okayanshul.docaction.document.image.ImageDocumentReader
import com.okayanshul.docaction.document.image.MlKitOcrEngine
import com.okayanshul.docaction.document.pdf.PdfBoxTextSource
import com.okayanshul.docaction.document.pdf.PdfDocumentReader
import com.okayanshul.docaction.document.pdf.SignatureFormatDetector
import com.okayanshul.docaction.document.csv.CsvScheduleSource
import com.okayanshul.docaction.document.spreadsheet.XlsxScheduleSource
import com.okayanshul.docaction.document.text.PlainTextDocumentReader
import com.okayanshul.docaction.domain.ActionTarget
import com.okayanshul.docaction.domain.Assumption
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.CandidateId
import com.okayanshul.docaction.domain.CandidateStatus
import com.okayanshul.docaction.domain.DateOrder
import com.okayanshul.docaction.domain.DocumentPipeline
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.ExtractionHints
import com.okayanshul.docaction.domain.FailureReason
import com.okayanshul.docaction.domain.GroupId
import com.okayanshul.docaction.domain.ImportId
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.domain.PipelineAnswers
import com.okayanshul.docaction.domain.PipelineResult
import com.okayanshul.docaction.domain.Stage
import com.okayanshul.docaction.domain.TermBounds
import com.okayanshul.docaction.extraction.EngineScheduleFinder
import com.okayanshul.docaction.imports.source.DocumentImages
import com.okayanshul.docaction.imports.source.SourceEvidence
import com.okayanshul.docaction.imports.source.SourceLocator
import com.okayanshul.docaction.timetable.TimetableStore
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives one import, from picking a file to events in the calendar.
 *
 * The ordering rule the whole product rests on lives here: **nothing outside the app changes
 * until [write] is called**, and [write] is reachable only from the confirm step. Everything
 * before it — reading, questions, review, editing — is undone by walking away.
 *
 * State is one sealed value rather than a bag of flags, so "writing" and "asking a question"
 * cannot both be true, and the UI has nothing to reconcile.
 */
class ImportViewModel(application: Application) : AndroidViewModel(application) {

    private val staging = DocumentStaging(application)
    private val journal = ImportJournal(application)
    private val ocr = MlKitOcrEngine(application)
    private val settings = ReminderSettings(application)
    private val timetables = TimetableStore(application)
    private val zone: ZoneId = ZoneId.systemDefault()

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private var source: DocumentSource? = null
    private var answers = PipelineAnswers()
    private var hints = ExtractionHints()
    private var running: Job? = null

    /** An import that was interrupted and can still be picked up, or null. */
    private val _interrupted = MutableStateFlow<Interrupted?>(null)
    val interrupted: StateFlow<Interrupted?> = _interrupted.asStateFlow()

    init {
        PdfBoxTextSource.initialise(application)
        // Documents from an import that died without cleaning up do not linger. Only
        // genuinely abandoned ones — a second import opened alongside this one keeps its own.
        viewModelScope.launch {
            // Read the journal first: an interrupted import can easily be older than the
            // abandonment threshold, and sweeping it would delete the document behind the
            // offer we are about to make.
            val saved = journal.interrupted()
            _interrupted.value = saved
            staging.sweepAbandoned(
                keep = setOfNotNull(saved?.let { File(it.source.uri).parentFile }),
            )
        }
    }

    /**
     * Picks up an import that process death interrupted.
     *
     * The pipeline is re-run rather than a saved result restored — see [ImportJournal]. The
     * user sees the processing screen again for a second or two, which is honest: work is
     * genuinely happening.
     */
    fun resume() {
        val saved = _interrupted.value ?: return
        // Adopt the interrupted import's staged document, so finishing or discarding this
        // one cleans up the right directory.
        staging.adopt(File(saved.source.uri))
        source = saved.source
        answers = saved.answers
        hints = saved.hints
        _interrupted.value = null
        run()
    }

    fun discardInterrupted() {
        val saved = _interrupted.value ?: return
        _interrupted.value = null
        viewModelScope.launch {
            journal.forget()
            runCatching { File(saved.source.uri).parentFile?.deleteRecursively() }
        }
    }

    /** The import was abandoned: the document goes with it. */
    override fun onCleared() {
        staging.clear()
    }

    private val executor by lazy {
        CalendarEventExecutor(
            context = getApplication(),
            createdEvents = Databases.createdEvents(getApplication()),
        )
    }

    private fun pipeline(): DocumentPipeline {
        val resolve: (DocumentSource) -> File? = { staging.fileFor(it) }
        return DocumentPipeline(
            detector = SignatureFormatDetector(resolve),
            readers = listOf(
                PdfDocumentReader(fileFor = resolve, ocr = ocr),
                ImageDocumentReader(ocr),
                PlainTextDocumentReader(resolve),
            ),
            schedules = EngineScheduleFinder(),
            zone = zone,
            scheduleSources = listOf(XlsxScheduleSource(resolve), CsvScheduleSource(resolve)),
        )
    }

    // --- reading ---

    /** Where the camera app should put the photo. See [DocumentStaging.captureTarget]. */
    fun captureTarget(): Uri = staging.captureTarget()

    fun start(uri: Uri) {
        val staged = staging.stage(uri)
        // The hand-off copy has served its purpose the moment the real one exists.
        staging.clearCaptures()
        if (staged == null) {
            _state.value = ImportState.Failed(FailureReason.PermissionRevoked, "that file")
            return
        }
        source = staged
        answers = PipelineAnswers()
        hints = ExtractionHints()
        run()
    }

    /** Someone shared text rather than a file — a message, an email body, a note. */
    fun startText(text: String) {
        val staged = staging.stageText(text)
        if (staged == null) {
            _state.value = ImportState.Failed(FailureReason.Empty, "that text")
            return
        }
        source = staged
        answers = PipelineAnswers()
        hints = ExtractionHints()
        run()
    }

    /** Cancellable at every stage boundary. Cancelling changes nothing, here or outside. */
    fun cancel() {
        running?.cancel()
        running = null
        staging.clear()
        source = null
        _state.value = ImportState.Idle
        viewModelScope.launch { journal.forget() }
    }

    fun answer(groupId: GroupId) {
        answers = answers.copy(selectedGroup = groupId)
        run()
    }

    /** Back to the list of schedules, with the previous choice forgotten. */
    fun chooseAnotherSchedule() {
        answers = answers.copy(selectedGroup = null)
        run()
    }

    /**
     * Which way round this document writes its dates.
     *
     * Applied to the whole document, not one entry — a date-order question is one question,
     * not forty-two — and recorded so a resumed import does not ask again.
     */
    fun answer(order: DateOrder) {
        answers = answers.copy(dateOrder = order)
        run()
    }

    fun answer(term: TermBounds) {
        answers = answers.copy(term = term)
        run()
    }

    private fun run() {
        val document = source ?: return
        running?.cancel()
        running = viewModelScope.launch {
            _state.value = ImportState.Processing(document.displayName, initialStages(), null, null)

            val result = runCatching {
                pipeline().run(document, hints = hints, answers = answers) { progress ->
                    _state.value = ImportState.Processing(
                        documentName = document.displayName,
                        stages = stagesFor(progress.stage),
                        detail = "Page ${progress.index} of ${progress.total}"
                            .takeIf { progress.determinate },
                        determinate = (progress.index.toFloat() / progress.total)
                            .takeIf { progress.determinate },
                    )
                }
            }.getOrElse { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                // Anything the pipeline did not classify is still not a stack trace to the
                // user: an unclassified failure is reported as one we could not process.
                //
                // The type is logged, though — never the message, which routinely quotes
                // document content. A catch-all that discards the cause entirely turns every
                // release-only breakage into a guessing game, which is exactly what happened
                // when minification broke the recogniser and every photo said the same
                // unhelpful thing.
                android.util.Log.w(LOG_TAG, "pipeline failed: ${failure::class.java.name}")
                _state.value = ImportState.Failed(FailureReason.ProcessingUnavailable, document.displayName)
                return@launch
            }

            // From here on the user has invested attention, so the import is worth being
            // able to resume. Recorded before the state changes, not after, because process
            // death does not wait for the frame to render.
            if (result !is PipelineResult.Failed) journal.remember(document, answers, hints)

            _state.value = when (result) {
                is PipelineResult.Failed ->
                    ImportState.Failed(result.reason, document.displayName, hints.cropRegion != null)

                is PipelineResult.NeedsAnswers ->
                    ImportState.Asking(document.displayName, result.questions.first())

                is PipelineResult.Ready -> {
                    val review = result.review
                    // Nothing to tick is nothing to review. Showing "0 events found" with an
                    // empty list and a dead Continue button is a screen that exists only to
                    // waste the user's time; the honest answer is that we found nothing.
                    if (review.candidates.isEmpty()) {
                        ImportState.Failed(
                            reason = FailureReason.NothingActionable,
                            documentName = document.displayName,
                            afterCrop = hints.cropRegion != null,
                            // Only when they chose it: otherwise this is the document being
                            // unreadable, not one section of it.
                            emptySchedule = answers.selectedGroup?.let { review.group?.label },
                        )
                    } else {
                        ImportState.Reviewing(
                            review = review,
                            selected = review.candidates.filter(::startsTicked).map { it.id }.toSet(),
                        )
                    }
                }
            }
        }
    }

    // --- rescue: "show us the part you need" ---

    /**
     * Opens the rescue screen for the document that just disappointed us.
     *
     * Deliberately reachable only from a failure or from review, never offered up front.
     * Asking someone to draw a box around their own timetable before we have even tried is
     * an admission that the engine does not work.
     */
    fun beginRescue() {
        val document = source ?: return
        viewModelScope.launch {
            _state.value = ImportState.Rescuing(document.displayName, pageCount = 1, page = 0, image = null)
            val images = images()
            val count = images.pageCount()
            if (count == 0) {
                _state.value = ImportState.Failed(FailureReason.PermissionRevoked, document.displayName)
                return@launch
            }
            _state.value = ImportState.Rescuing(
                documentName = document.displayName,
                pageCount = count,
                page = 0,
                image = images.render(0, RESCUE_WIDTH_PX),
            )
        }
    }

    fun rescuePage(index: Int) {
        val current = _state.value as? ImportState.Rescuing ?: return
        if (index == current.page || index !in 0 until current.pageCount) return

        current.image?.recycle()
        // The crop goes with the page it was drawn on; carrying it over would silently
        // apply a rectangle the user chose while looking at something else.
        _state.value = current.copy(page = index, image = null, crop = null)

        viewModelScope.launch {
            val rendered = images().render(index, RESCUE_WIDTH_PX)
            (_state.value as? ImportState.Rescuing)
                ?.takeIf { it.page == index }
                ?.let { _state.value = it.copy(image = rendered) }
                ?: rendered?.recycle()
        }
    }

    fun setCrop(region: com.okayanshul.docaction.domain.BoundingBox?) {
        val current = _state.value as? ImportState.Rescuing ?: return
        _state.value = current.copy(crop = region)
    }

    /** Reads the document again, looking only where the user pointed. */
    fun applyRescue() {
        val current = _state.value as? ImportState.Rescuing ?: return
        current.image?.recycle()
        hints = ExtractionHints(
            pageSelection = listOf(current.page).takeIf { current.isPaged },
            cropRegion = current.crop,
        )
        // Answers are kept: the term the user already chose is still the term they want.
        run()
    }

    /**
     * Which rows begin ticked.
     *
     * Read rows, obviously. Beyond that the line is drawn at *how big the leap was*:
     *
     * - An **assumed end time** stays ticked. The document gave a start; we gave it an hour,
     *   said so on the row, and made it editable. Leaving it unticked would mean the natural
     *   path for a bill or a ticket ends on "Nothing selected" with a dead button — a worse
     *   first impression than the honest failure it replaced.
     * - An **all-day item made from a bare date** does not. Turning "15 March" into an event
     *   is a larger claim, and it deserves a deliberate tap.
     *
     * Both are flagged and labelled either way. This decides only what a hurried user gets
     * by default, never what they are told.
     */
    private fun startsTicked(candidate: CalendarEventCandidate): Boolean = when {
        candidate.status == CandidateStatus.Ready -> true
        candidate.assumptions.isEmpty() -> false
        else -> candidate.assumptions.all { it is Assumption.EndTime }
    }

    // --- review ---

    fun toggle(id: CandidateId) {
        update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    }

    fun filterAttention(only: Boolean) = update { it.copy(showOnlyAttention = only) }

    fun selectAll(select: Boolean) = update {
        it.copy(selected = if (select) it.review.candidates.map { c -> c.id }.toSet() else emptySet())
    }

    fun beginEdit(id: CandidateId?) = update { it.copy(editing = id) }

    /**
     * Opens "where did this come from?" and goes back to the document to answer it.
     *
     * The sheet appears immediately with a spinner rather than after the page has rendered:
     * re-reading a PDF page takes a moment, and a tap that appears to do nothing for half a
     * second reads as a broken button.
     */
    fun showSource(id: CandidateId) {
        val current = _state.value as? ImportState.Reviewing ?: return
        val candidate = current.review.candidates.firstOrNull { it.id == id } ?: return
        _state.value = current.copy(viewingSource = id, evidence = null)

        viewModelScope.launch {
            val found = locator(current.review).locate(candidate, SOURCE_WIDTH_PX)
            // Only apply it if the user is still looking at this row.
            (_state.value as? ImportState.Reviewing)
                ?.takeIf { it.viewingSource == id }
                ?.let { _state.value = it.copy(evidence = found) }
                ?: (found as? SourceEvidence.Page)?.image?.recycle()
        }
    }

    fun hideSource() {
        val current = _state.value as? ImportState.Reviewing ?: return
        // A page bitmap is megabytes; it goes as soon as it is off screen.
        (current.evidence as? SourceEvidence.Page)?.image?.recycle()
        _state.value = current.copy(viewingSource = null, evidence = null)
    }

    private fun locator(review: com.okayanshul.docaction.domain.ReviewSet) = SourceLocator(
        file = staging.fileFor(review.source),
        format = review.format,
        openStream = { File(review.source.uri).inputStream() },
    )

    /**
     * Images of the document currently being imported.
     *
     * The format is re-detected rather than remembered, because rescue is reachable from a
     * failure — at which point there is no [ReviewSet][com.okayanshul.docaction.domain.ReviewSet]
     * to have remembered it from.
     */
    private suspend fun images(): DocumentImages {
        val document = source!!
        val file = staging.fileFor(document)
        val format = (SignatureFormatDetector { staging.fileFor(it) }.detect(document) as? Outcome.Success)
            ?.value
            ?: com.okayanshul.docaction.domain.DocumentFormat.Unsupported
        return DocumentImages(file, format) { File(document.uri).inputStream() }
    }

    /**
     * Applies a user's correction.
     *
     * The corrected row is ticked, because a person who has just typed a value has said
     * more clearly than any tick box that they want it.
     */
    fun applyEdit(id: CandidateId, edit: (CalendarEventCandidate) -> CalendarEventCandidate?) {
        val current = _state.value as? ImportState.Reviewing ?: return
        val existing = current.review.candidates.firstOrNull { it.id == id } ?: return
        val corrected = edit(existing) ?: return

        _state.value = current.copy(
            review = current.review.copy(
                candidates = current.review.candidates.map { if (it.id == id) corrected else it },
            ),
            selected = current.selected + id,
            editing = null,
        )
    }

    private inline fun update(block: (ImportState.Reviewing) -> ImportState.Reviewing) {
        val current = _state.value as? ImportState.Reviewing ?: return
        _state.value = block(current)
    }

    // --- confirm ---

    fun toConfirm() {
        val current = _state.value as? ImportState.Reviewing ?: return
        viewModelScope.launch {
            val permissions = CalendarTargets(getApplication())
            if (!permissions.canRead() || !permissions.canWrite()) {
                _state.value = ImportState.Confirming(
                    review = current.review,
                    chosen = current.chosen,
                    targets = emptyList(),
                    target = null,
                    reminders = settings.preferences.first(),
                    needsPermission = true,
                )
                return@launch
            }
            loadTargets(current.review, current.chosen)
        }
    }

    /** Called once the calendar permission dialog resolves, whichever way it went. */
    fun permissionResolved(granted: Boolean) {
        val current = _state.value as? ImportState.Confirming ?: return
        if (!granted) {
            // A dead end with dignity: the confirm screen stays, explains, and offers
            // Settings. It does not nag, and it does not pretend the import continued.
            _state.value = current.copy(needsPermission = true, denied = true)
            return
        }
        viewModelScope.launch { loadTargets(current.review, current.chosen) }
    }

    private suspend fun loadTargets(
        review: com.okayanshul.docaction.domain.ReviewSet,
        chosen: List<CalendarEventCandidate>,
    ) {
        val targets = (executor.targets() as? Outcome.Success)?.value.orEmpty()
        // Pre-selected only when there is exactly one, and shown either way. With several,
        // the user picks: silently writing 42 events into a work account they forgot was on
        // the device is a real harm and an entirely avoidable one.
        val only = targets.singleOrNull()

        _state.value = ImportState.Confirming(
            review = review,
            chosen = chosen,
            targets = targets,
            target = only,
            duplicates = only?.let { duplicatesFor(chosen, it) } ?: 0,
            reminders = settings.preferences.first(),
            // Looked up before anything is written, so a schedule the user would lose is part
            // of the summary rather than a casualty of it.
            timetableCollision = timetableCollisionFor(review, chosen),
        )
    }

    /**
     * The stored timetable this import would land on, if any.
     *
     * Only asked for a recurring import, because only those are kept as timetables at all.
     */
    private suspend fun timetableCollisionFor(
        review: com.okayanshul.docaction.domain.ReviewSet,
        chosen: List<CalendarEventCandidate>,
    ) = if (chosen.none { it.recurrence != null }) {
        null
    } else {
        runCatching {
            timetables.collisionFor(
                label = timetableLabelFor(review),
                sourceIdentity = timetableIdentityFor(review),
            )
        }.getOrNull()
    }

    private fun timetableLabelFor(review: com.okayanshul.docaction.domain.ReviewSet) =
        review.group?.label ?: review.source.displayName

    private fun timetableIdentityFor(review: com.okayanshul.docaction.domain.ReviewSet) =
        TimetableStore.identityOf(
            documentHash = TimetableStore.hashOf(staging.fileFor(review.source)),
            groupId = review.selectedGroup?.value,
        )

    /** Records what the user decided about a timetable their import would overwrite. */
    fun setTimetableResolution(
        resolution: com.okayanshul.docaction.timetable.TimetableResolution?,
    ) {
        val current = _state.value as? ImportState.Confirming ?: return
        _state.value = current.copy(timetableResolution = resolution)
    }

    fun chooseTarget(target: ActionTarget) {
        val current = _state.value as? ImportState.Confirming ?: return
        _state.value = current.copy(target = target, duplicates = 0)
        viewModelScope.launch {
            val duplicates = duplicatesFor(current.chosen, target)
            (_state.value as? ImportState.Confirming)
                ?.takeIf { it.target == target }
                ?.let { _state.value = it.copy(duplicates = duplicates) }
        }
    }

    /**
     * Back out of the consent step.
     *
     * Returns to review with the same rows ticked. Nothing has been written at this point,
     * so this genuinely costs the user nothing — which is the property that makes the
     * confirm step feel safe to reach rather than something to avoid.
     */
    fun backToReview() {
        val current = _state.value as? ImportState.Confirming ?: return
        _state.value = ImportState.Reviewing(
            review = current.review,
            selected = current.chosen.map { it.id }.toSet(),
        )
    }

    fun setReminders(enabled: Boolean) {
        val current = _state.value as? ImportState.Confirming ?: return
        _state.value = current.copy(remindersEnabled = enabled)
    }

    fun setKeepTimetable(keep: Boolean) {
        val current = _state.value as? ImportState.Confirming ?: return
        _state.value = current.copy(keepAsTimetable = keep)
    }

    private suspend fun duplicatesFor(chosen: List<CalendarEventCandidate>, target: ActionTarget) =
        (executor.findDuplicates(chosen, target) as? Outcome.Success)?.value?.size ?: 0

    // --- the only method that changes anything outside the app ---

    fun write() {
        val current = _state.value as? ImportState.Confirming ?: return
        val target = current.target as? CalendarTarget ?: return
        // Belt and braces with `canWrite`: an unanswered question about someone's stored
        // timetable must not be resolvable by reaching this function some other way.
        if (current.awaitingTimetableDecision) return
        val importId = ImportId(UUID.randomUUID().toString())

        viewModelScope.launch {
            _state.value = ImportState.Writing(0, current.chosen.size)

            val outcome = executor.execute(importId, current.chosen, target) { done, total ->
                _state.value = ImportState.Writing(done, total)
            }

            _state.value = when (outcome) {
                is Outcome.Failure ->
                    ImportState.Failed(outcome.reason, current.review.source.displayName)

                is Outcome.Success -> finished(importId, outcome.value, target, current)
                is Outcome.Partial -> finished(importId, outcome.value, target, current)
            }

            if (current.remindersEnabled) {
                armReminders(importId, current.chosen, current.reminders)
            }

            // After the calendar write and outside its result, like reminders: failing to
            // remember a timetable must never make a successful import look failed.
            if (current.keepAsTimetable && current.canKeepAsTimetable) {
                runCatching {
                    timetables.save(
                        label = timetableLabelFor(current.review),
                        candidates = current.chosen,
                        term = answers.term ?: ImportViewModel.suggestedTerm(),
                        importId = importId,
                        sourceName = current.review.source.displayName,
                        sourceHash = TimetableStore.hashOf(staging.fileFor(current.review.source)),
                        sourceIdentity = timetableIdentityFor(current.review),
                        // Null is safe: with a collision outstanding the store declines to
                        // write rather than choosing for the user. `canWrite` means we never
                        // get here in that case, and if we somehow did, nothing is destroyed.
                        resolution = current.timetableResolution,
                    )
                }
            }
            staging.clear()
            journal.forget()
        }
    }

    private fun finished(
        importId: ImportId,
        report: com.okayanshul.docaction.domain.ExecutionReport,
        target: CalendarTarget,
        confirming: ImportState.Confirming,
    ) = ImportState.Finished(
        importId = importId,
        // The count comes from the read-back inside the executor, never from what we
        // attempted — "38 added" has to mean 38 events exist.
        written = report.written.size,
        failed = report.failed.size,
        calendarLabel = target.label,
        remindersOn = confirming.remindersEnabled,
    )

    /**
     * Plans and arms the rolling reminder window for what was just written.
     *
     * Deliberately after the calendar write and outside its result: a reminder that fails to
     * arm must never make a successful import look failed.
     */
    private suspend fun armReminders(
        importId: ImportId,
        candidates: List<CalendarEventCandidate>,
        preferences: ReminderPreferences,
    ) {
        runCatching {
            val context = getApplication<Application>()
            NotificationPublisher(context).ensureChannels()

            val dao = Databases.reminders(context)
            val ladder = preferences.toLadder()
            val planner = ReminderPlanner()
            candidates.forEach { dao.upsert(planner.plan(importId, it, ladder)) }

            ReminderScheduler(context, dao).armWindow()
        }
    }

    /** Removes exactly what this import created, and nothing else. */
    fun undo() {
        val finished = _state.value as? ImportState.Finished ?: return
        viewModelScope.launch {
            _state.value = ImportState.Writing(0, finished.written)
            runCatching {
                executor.revert(finished.importId)
                ReminderScheduler(getApplication(), Databases.reminders(getApplication()))
                    .cancelImport(finished.importId)
            }
            source = null
            answers = PipelineAnswers()
            _state.value = ImportState.Idle
        }
    }

    fun dismiss() {
        staging.clear()
        source = null
        answers = PipelineAnswers()
        hints = ExtractionHints()
        _state.value = ImportState.Idle
        viewModelScope.launch { journal.forget() }
    }

    // --- progress presentation ---

    private fun initialStages() = LABELS.mapIndexed { index, label ->
        StageLine(label, if (index == 0) StageState.Active else StageState.Pending)
    }

    /** Three honest lines. Stages the document does not need are never shown as running. */
    private fun stagesFor(stage: Stage): List<StageLine> {
        val reached = when (stage) {
            Stage.Validating, Stage.DetectingFormat, Stage.ReadingDocument -> 0
            Stage.DetectingStructure, Stage.FindingDates -> 1
            Stage.FindingTimes, Stage.BuildingSchedule -> 2
        }
        return LABELS.mapIndexed { index, label ->
            StageLine(
                label = label,
                state = when {
                    index < reached -> StageState.Done
                    index == reached -> StageState.Active
                    else -> StageState.Pending
                },
            )
        }
    }

    companion object {
        /**
         * How wide a page is rendered for Source View.
         *
         * Fixed rather than measured from the sheet: it is re-read on a background thread
         * before the sheet has laid out, and a page a little wider than the screen still
         * reads correctly while a page sized from a stale measurement does not.
         */
        private const val LOG_TAG = "DocAction"

        private const val SOURCE_WIDTH_PX = 1080

        /** Rescue renders the same way, and for the same reason. */
        private const val RESCUE_WIDTH_PX = 1080

        private val LABELS = listOf(
            "Reading document",
            "Finding your schedule",
            "Checking dates and times",
        )

        /**
         * The end date offered — never applied — when a weekly schedule has none.
         *
         * Fifteen weeks is a typical term, but it is a starting position in a date picker,
         * not an assumption: the pipeline refuses to build a recurrence until the user has
         * actually chosen.
         */
        fun suggestedTerm(today: LocalDate = LocalDate.now()): TermBounds =
            TermBounds(today, today.plusWeeks(15))
    }
}
