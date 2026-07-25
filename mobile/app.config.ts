import 'dotenv/config';
import { ExpoConfig, ConfigContext } from 'expo/config';

const apiUrl = process.env.API_URL;
const googleServicesJson = process.env.GOOGLE_SERVICES_JSON;
const googleServicesPlist = process.env.GOOGLE_SERVICES_PLIST;

export default ({ config }: ConfigContext): ExpoConfig => ({
  ...config,
  name: 'CriticalAsset Maintain',
  slug: 'criticalasset-maintain',
  version: '1.0.0',
  orientation: 'portrait',
  icon: './assets/images/icon.png',
  scheme: 'criticalassetmaintain',
  userInterfaceStyle: 'automatic',
  newArchEnabled: false,
  notification: {
    icon: './assets/images/notification.png'
  },
  splash: {
    image: './assets/images/splash.png',
    resizeMode: 'contain',
    backgroundColor: '#ffffff'
  },
  updates: {
    fallbackToCacheTimeout: 0,
    assetPatternsToBeBundled: ['**/*']
  },
  ios: {
    bundleIdentifier: 'com.criticalasset.maintain',
    buildNumber: '1',
    jsEngine: 'hermes',
    supportsTablet: false,
    runtimeVersion: '1.0.0',
    googleServicesFile: googleServicesPlist ?? './GoogleService-Info.plist',
    infoPlist: {
      ITSAppUsesNonExemptEncryption: false
    }
  },
  android: {
    adaptiveIcon: {
      foregroundImage: './assets/images/adaptive-icon.png',
      backgroundColor: '#ffffff'
    },
    versionCode: 1,
    package: 'com.criticalasset.maintain',
    jsEngine: 'hermes',
    googleServicesFile:
      googleServicesJson ?? './android/app/google-services.json',
    runtimeVersion: '1.0.0'
  },
  web: {
    favicon: './assets/images/favicon.png'
  },
  extra: {
    API_URL: apiUrl
  },
  plugins: [
    'react-native-nfc-manager',
    'expo-font',
    'expo-notifications',
    '@react-native-community/datetimepicker',
    '@react-native-firebase/app',
    './plugins/ios/withFmtXcode26Fix',
    [
      'expo-camera',
      {
        cameraPermission: 'Allow CriticalAsset Maintain to access camera.'
      }
    ],
    [
      'expo-build-properties',
      {
        ios: {
          useFrameworks: 'static',
          deploymentTarget: '15.1'
        },
        android: {
          compileSdkVersion: 35,
          targetSdkVersion: 35
        }
      }
    ]
  ]
});
