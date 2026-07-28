import SuspenseLoader from '../../../components/SuspenseLoader';
import { useContext, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import useAuth from '../../../hooks/useAuth';
import { CustomSnackBarContext } from '../../../contexts/CustomSnackBarContext';

export default function OauthFailure() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { loginInternal } = useAuth();
  const error = searchParams.get('error');
  const { showSnackBar } = useContext(CustomSnackBarContext);
  const navigate = useNavigate();

  useEffect(() => {
    if (error) {
      // Hand the failure back to the mobile app the same way OauthSuccess
      // hands back the token, so its expo-web-browser auth session resolves
      // and it can show the reason. Without this the in-app browser is left
      // sitting on the web login form and sign-in fails silently.
      // A no-op in a regular browser — nothing is registered for the scheme.
      window.location.href = `criticalassetmaintain://oauth2/failure?error=${encodeURIComponent(
        error
      )}`;
      showSnackBar(error, 'error');
      navigate('/account/login');
    }
  }, [error]);
  return <SuspenseLoader />;
}
