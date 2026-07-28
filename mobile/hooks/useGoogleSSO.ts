import * as WebBrowser from 'expo-web-browser';
import { useState } from 'react';
import { getApiUrl } from '../config';
import useAuth from './useAuth';

// expo-web-browser's auth session (ASWebAuthenticationSession on iOS,
// Custom Tabs on Android) watches for a redirect to this scheme to know the
// flow is done. The backend always lands on the WEB app's success or failure
// page (${PUBLIC_FRONT_URL}/oauth2/success?token=... or
// /oauth2/failure?error=...) — that page then does a client-side redirect to
// this scheme to hand the outcome back to the app (see
// frontend/src/content/pages/Oauth/OauthSuccess.tsx and OauthFailure.tsx).
// No backend changes needed; the backend doesn't know or care this call came
// from mobile.
const REDIRECT_URL = 'criticalassetmaintain://oauth2/success';

export type SSOResult = {
  type: 'success' | 'cancelled' | 'error';
  // Reason the backend gave, when it gave one (e.g. the email domain isn't
  // allow-listed for SSO). Worth showing verbatim — it's what tells the user
  // whether to retry or ask an admin for access.
  message?: string;
};

// Avoid relying on the global URL/URLSearchParams (spotty support across
// Hermes versions) for a single query param.
const getQueryParam = (url: string, name: string): string | null => {
  const match = url.match(new RegExp(`[?&]${name}=([^&]+)`));
  return match ? decodeURIComponent(match[1]) : null;
};

export default function useGoogleSSO() {
  const { loginWithToken } = useAuth();
  const [loading, setLoading] = useState(false);

  const signInWithGoogle = async (): Promise<SSOResult> => {
    setLoading(true);
    try {
      const apiUrl = await getApiUrl();
      const authorizeUrl = `${apiUrl}oauth2/authorize/google`;
      const result = await WebBrowser.openAuthSessionAsync(
        authorizeUrl,
        REDIRECT_URL
      );
      if (result.type === 'cancel' || result.type === 'dismiss') {
        return { type: 'cancelled' };
      }
      if (result.type !== 'success' || !result.url) {
        return { type: 'error' };
      }
      // The session is matched on the scheme alone, and the failure page
      // redirects to that same scheme — so a resolved session isn't
      // necessarily a successful sign-in.
      const error = getQueryParam(result.url, 'error');
      if (error) {
        return { type: 'error', message: error };
      }
      const token = getQueryParam(result.url, 'token');
      if (!token) {
        return { type: 'error' };
      }
      await loginWithToken(token);
      return { type: 'success' };
    } catch {
      return { type: 'error' };
    } finally {
      setLoading(false);
    }
  };

  return { signInWithGoogle, loading };
}
