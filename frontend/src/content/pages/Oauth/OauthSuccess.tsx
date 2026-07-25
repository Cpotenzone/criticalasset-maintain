import SuspenseLoader from '../../../components/SuspenseLoader';
import { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import useAuth from '../../../hooks/useAuth';

export default function OauthSuccess() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { loginInternal } = useAuth();
  const token = searchParams.get('token');
  useEffect(() => {
    if (token) {
      loginInternal(token);
      // Mobile app's expo-web-browser auth session is watching for a
      // redirect to this scheme to know the OAuth flow finished and hand
      // the token back to the app (see mobile/hooks/useGoogleSSO.ts). A
      // no-op on desktop/regular mobile browsers — there's no app
      // registered for the scheme there, so nothing happens.
      window.location.href = `criticalassetmaintain://oauth2/success?token=${encodeURIComponent(
        token
      )}`;
    }
  }, [token]);
  return <SuspenseLoader />;
}
