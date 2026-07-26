import { useState } from 'react';
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Typography
} from '@mui/material';
import AutoAwesomeTwoToneIcon from '@mui/icons-material/AutoAwesomeTwoTone';
import { useTranslation } from 'react-i18next';
import api from '../../../utils/api';

interface AiInsights {
  success: boolean;
  insights?: string;
  engine?: string;
}

interface AiInsightsCardProps {
  // e.g. `work-orders/12/ai-insights` or `assets/7/ai-insights`
  url: string;
}

/**
 * On-demand briefing from CriticalAsset's own expert models (CLEO/MAX).
 * Renders the four titled sections the backend prompts for, and shows which
 * engine actually answered.
 */
export default function AiInsightsCard({ url }: AiInsightsCardProps) {
  const { t } = useTranslation();
  const [loading, setLoading] = useState<boolean>(false);
  const [insights, setInsights] = useState<AiInsights | null>(null);

  const generate = () => {
    setLoading(true);
    api
      .get<AiInsights>(url)
      .then(setInsights)
      .catch(() => setInsights({ success: false }))
      .finally(() => setLoading(false));
  };

  return (
    <Card variant="outlined">
      <CardContent>
        <Box
          display="flex"
          flexDirection="row"
          justifyContent="space-between"
          alignItems="center"
        >
          <Box display="flex" alignItems="center" gap={1}>
            <AutoAwesomeTwoToneIcon color="primary" fontSize="small" />
            <Typography variant="h6">{t('ai_insights')}</Typography>
            {insights?.success && insights.engine && (
              <Chip
                size="small"
                color="primary"
                variant="outlined"
                label={t('ai_powered_by', { engine: insights.engine })}
              />
            )}
          </Box>
          <Button
            size="small"
            variant="outlined"
            onClick={generate}
            disabled={loading}
            startIcon={loading ? <CircularProgress size={14} /> : undefined}
          >
            {insights ? t('ai_regenerate') : t('ai_generate')}
          </Button>
        </Box>
        {insights && !insights.success && (
          <Typography sx={{ mt: 1 }} color="error" variant="body2">
            {t('ai_insights_failed')}
          </Typography>
        )}
        {insights?.success && (
          <Typography
            sx={{ mt: 1, whiteSpace: 'pre-wrap' }}
            variant="body2"
            color="text.primary"
          >
            {insights.insights}
          </Typography>
        )}
      </CardContent>
    </Card>
  );
}
