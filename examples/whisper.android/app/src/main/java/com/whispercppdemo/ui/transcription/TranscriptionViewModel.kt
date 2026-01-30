package com.whispercppdemo.ui.transcription

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercppdemo.media.decodeWaveFile
import com.whispercppdemo.recorder.Recorder
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val LOG_TAG = "TranscriptionViewModel"

class TranscriptionViewModel(private val application: Application) : AndroidViewModel(application) {
    var isRecording by mutableStateOf(false)
        private set
    var isTranscribing by mutableStateOf(false)
        private set
    var transcriptionResult by mutableStateOf("")
        private set
    var statusMessage by mutableStateOf("")
        private set
    var canTranscribe by mutableStateOf(false)
        private set
    var processingTime by mutableStateOf("")
        private set

    private val modelsPath = File(application.filesDir, "models")
    private val recordingsPath = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WhisperTranscriptions")
    private var recorder: Recorder = Recorder()
    private var whisperContext: WhisperContext? = null
    private var currentRecordingFile: File? = null

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TranscriptionViewModel(application)
            }
        }
    }

    init {
        viewModelScope.launch {
            setupDirectories()
            loadWhisperModel()
        }
    }

    private suspend fun setupDirectories() {
        withContext(Dispatchers.IO) {
            if (!recordingsPath.exists()) {
                recordingsPath.mkdirs()
            }
        }
    }

    private suspend fun loadWhisperModel() {
        withContext(Dispatchers.IO) {
            try {
                statusMessage = "Loading Whisper model..."
                val modelFile = File(modelsPath, "ggml-base.en-q8_0.bin")
                
                if (!modelFile.exists()) {
                    statusMessage = "Model not found. Please ensure ggml-base.en-q8_0.bin is in the models directory."
                    return@withContext
                }

                whisperContext = WhisperContext.createContextFromFile(modelFile.absolutePath)
                canTranscribe = true
                statusMessage = "Ready to transcribe"
                Log.d(LOG_TAG, "Whisper model loaded successfully")
            } catch (e: Exception) {
                statusMessage = "Error loading model: ${e.message}"
                Log.e(LOG_TAG, "Error loading Whisper model", e)
            }
        }
    }

    fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        viewModelScope.launch {
            try {
                val timestamp = System.currentTimeMillis()
                currentRecordingFile = File(recordingsPath, "recording_$timestamp.wav")
                
                isRecording = true
                transcriptionResult = ""
                statusMessage = "Recording..."
                
                recorder.startRecording(currentRecordingFile!!) { exception ->
                    Log.e(LOG_TAG, "Recording error", exception)
                    statusMessage = "Recording error: ${exception.message}"
                    isRecording = false
                }
            } catch (e: Exception) {
                statusMessage = "Failed to start recording: ${e.message}"
                Log.e(LOG_TAG, "Error starting recording", e)
                isRecording = false
            }
        }
    }

    private fun stopRecording() {
        viewModelScope.launch {
            try {
                recorder.stopRecording()
                isRecording = false
                statusMessage = "Recording stopped. Processing..."
                
                currentRecordingFile?.let { file ->
                    transcribeAudioFile(file)
                }
            } catch (e: Exception) {
                statusMessage = "Failed to stop recording: ${e.message}"
                Log.e(LOG_TAG, "Error stopping recording", e)
            }
        }
    }

    fun transcribeFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                isTranscribing = true
                transcriptionResult = ""
                statusMessage = "Loading audio file..."
                
                // Copy URI content to a temporary file
                val tempFile = File(application.cacheDir, "temp_audio_${System.currentTimeMillis()}.wav")
                
                withContext(Dispatchers.IO) {
                    application.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                
                transcribeAudioFile(tempFile)
                
                // Clean up temp file
                tempFile.delete()
            } catch (e: Exception) {
                statusMessage = "Error loading audio file: ${e.message}"
                Log.e(LOG_TAG, "Error transcribing from URI", e)
                isTranscribing = false
            }
        }
    }

    private suspend fun transcribeAudioFile(file: File) {
        withContext(Dispatchers.IO) {
            try {
                isTranscribing = true
                statusMessage = "Transcribing audio..."
                
                if (whisperContext == null) {
                    statusMessage = "Whisper model not loaded"
                    isTranscribing = false
                    return@withContext
                }

                // Decode the wave file to float array
                val audioData = decodeWaveFile(file)
                Log.d(LOG_TAG, "Audio data length: ${audioData.size}")

                // Run transcription with line-level timestamps
                val startTime = System.currentTimeMillis()
                val fullTranscript = whisperContext?.transcribeData(audioData, printTimestamp = true) ?: ""
                val endTime = System.currentTimeMillis()
                
                processingTime = "Processing time: ${(endTime - startTime) / 1000.0}s"

                transcriptionResult = fullTranscript.trim().ifEmpty {
                    "No transcription available"
                }

                statusMessage = "Transcription complete!"
                Log.d(LOG_TAG, "Transcription: $transcriptionResult")
            } catch (e: Exception) {
                statusMessage = "Transcription error: ${e.message}"
                Log.e(LOG_TAG, "Error during transcription", e)
            } finally {
                isTranscribing = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            whisperContext?.release()
        }
    }
}
