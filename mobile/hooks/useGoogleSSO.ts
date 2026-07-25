import * as WebBrowser from 'expo-web-browser';
import { useState } from 'react';
import { getApiUrl } from '../config';
import useAuth from './useAuth';

// expo-web-browser's auth session (ASWebAuthenticationSession on iOS,
// Custom Tabs on Android) watches for a redirect to REDIRECT_URL to know
// the flow is done. The backend always lands on the WEB app's success page
// (${PUBLIC_FRONT_URL}/oauth2/success?token=...) — that page then does a
// client-side redirect to this scheme to hand the token back to the app
// (see frontend/src/content/pages/Oauth/OauthSuccess.tsx). No backend
// changes needed; the backend doesn't know or care this call came from
// mobile.
const REDIRECT_URL = 'criticalassetmaintain://oauth2/success';

export default function useGoogleSSO() {
  const { loginWithToken } = useAuth();
  const [loading, setLoading] = useState(false);

  const signInWithGoogle = async (): Promise<
    'success' | 'cancelled' | 'error'
  > => {
    setLoading(true);
    try {
      const apiUrl = await getApiUrl();
      const authorizeUrl = `${apiUrl}oauth2/authorize/google`;
      const result = await WebBrowser.openAuthSessionAsync(
        authorizeUrl,
        REDIRECT_URL
      );
      if (result.type === 'cancel' || result.type === 'dismiss') {
        return 'cancelled';
      }
      if (result.type !== 'success' || !result.url) {
        return 'error';
      }
      // Avoid relying on the global URL/URLSearchParams (spotty support
      // across Hermes versions) for a single query param.
      const match = result.url.match(/[?&]token=([^&]+)/);
      const token = match ? decodeURIComponent(match[1]) : null;
      if (!token) {
        return 'error';
      }
      await loginWithToken(token);
      return 'success';
    } catch {
      return 'error';
    } finally {
      setLoading(false);
    }
  };

  return { signInWithGoogle, loading };
}
