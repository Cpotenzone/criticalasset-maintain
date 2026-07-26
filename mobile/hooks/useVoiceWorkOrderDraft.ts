import { useState } from 'react';
import {
  ExpoSpeechRecognitionModule,
  useSpeechRecognitionEvent
} from 'expo-speech-recognition';
import api from '../utils/api';

export interface WorkOrderAiDraft {
  success: boolean;
  title?: string;
  description?: string;
  priority?: string;
}

// Voice → CLEO (CriticalAsset's own served models — no third-party AI):
// transcribe on-device (no audio ever leaves the phone for this step), then
// hand the plain transcript to /work-orders/ai-draft, which asks CLEO to
// turn it into a draft. The technician reviews every field before saving —
// this only pre-fills the form, it never creates the ticket itself.
export default function useVoiceWorkOrderDraft(
  onDraft: (draft: WorkOrderAiDraft) => void
) {
  const [isListening, setIsListening] = useState(false);
  const [isDrafting, setIsDrafting] = useState(false);
  const [transcript, setTranscript] = useState('');

  useSpeechRecognitionEvent('start', () => setIsListening(true));
  useSpeechRecognitionEvent('end', () => setIsListening(false));
  useSpeechRecognitionEvent('result', (event) => {
    setTranscript(event.results[0]?.transcript ?? '');
  });
  useSpeechRecognitionEvent('error', (event) => {
    setIsListening(false);
    onDraft({ success: false });
    console.warn('speech recognition error', event.error, event.message);
  });

  const startListening = async () => {
    const result = await ExpoSpeechRecognitionModule.requestPermissionsAsync();
    if (!result.granted) {
      onDraft({ success: false });
      return;
    }
    setTranscript('');
    ExpoSpeechRecognitionModule.start({
      lang: 'en-US',
      interimResults: true,
      continuous: false
    });
  };

  const stopListening = async () => {
    ExpoSpeechRecognitionModule.stop();
    // 'end' fires asynchronously right after stop(); give the final result
    // event a beat to land before reading `transcript`.
    await new Promise((resolve) => setTimeout(resolve, 400));
    if (!transcript.trim()) {
      onDraft({ success: false });
      return;
    }
    setIsDrafting(true);
    try {
      const draft = await api.post<WorkOrderAiDraft>('work-orders/ai-draft', {
        text: transcript
      });
      onDraft(draft);
    } catch (e) {
      onDraft({ success: false });
    } finally {
      setIsDrafting(false);
    }
  };

  return { isListening, isDrafting, transcript, startListening, stopListening };
}
