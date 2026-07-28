import SuspenseLoader from '../../../components/SuspenseLoader';
import { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import useAuth from '../../../hooks/useAuth';

export default function OauthSuccess() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { loginInternal } = useAuth();
  const token = searchParams.get('token');
  useEffect(() => {
    if (!token) return;
    // Hand the token to the mobile app FIRST. Its expo-web-browser auth session
    // is watching for this scheme and closes the in-app browser the instant it
    // sees the redirect, returning to the native app. Order matters:
    // loginInternal() logs into the web SPA and navigates it to the dashboard —
    // if that runs first, the web app renders *inside* the mobile in-app browser
    // instead of handing back (the "it opens a browser inside the app" bug).
    window.location.href = `criticalassetmaintain://oauth2/success?token=${encodeURIComponent(
      token
    )}`;
    // Desktop/regular-browser fallback only: the scheme above is unhandled there
    // (no app registered), so complete the web sign-in a beat later. On mobile
    // the browser has already closed by now, so this never runs.
    const t = setTimeout(() => loginInternal(token), 600);
    return () => clearTimeout(t);
  }, [token]);
  return <SuspenseLoader />;
}
