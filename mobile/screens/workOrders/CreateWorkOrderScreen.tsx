import { RootStackScreenProps } from '../../types';
import { View } from '../../components/Themed';
import Form from '../../components/form';
import * as Yup from 'yup';
import { StyleSheet } from 'react-native';
import { Button, Text } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import {
  getCustomFieldsIFields,
  getCustomFieldsRequiredShape,
  IField
} from '../../models/form';
import { useContext, useState } from 'react';
import { CompanySettingsContext } from '../../contexts/CompanySettingsContext';
import { getImageAndFiles } from '../../utils/overall';
import { useDispatch, useSelector } from '../../store';
import { addWorkOrder } from '../../slices/workOrder';
import { CustomSnackBarContext } from '../../contexts/CustomSnackBarContext';
import { formatWorkOrderValues, getWorkOrderFields } from '../../utils/fields';
import { formatCustomFields } from '../../utils/formatters';
import { assetStatuses } from '../../models/asset';
import { useAppTheme } from '../../custom-theme';
import { getErrorMessage } from '../../utils/api';
import { CustomFieldEntityType } from '../../models/customField';
import useUnsavedChanges from '../../hooks/useUnsavedChanges';
import useVoiceWorkOrderDraft, {
  WorkOrderAiDraft
} from '../../hooks/useVoiceWorkOrderDraft';

export default function CreateWorkOrderScreen({
  navigation,
  route
}: RootStackScreenProps<'AddWorkOrder'>) {
  const { t } = useTranslation();
  const [isFormDirty, setIsFormDirty] = useState(false);
  const theme = useAppTheme();
  const { uploadFiles, getWOFieldsAndShapes } = useContext(
    CompanySettingsContext
  );
  const { showSnackBar } = useContext(CustomSnackBarContext);
  const dispatch = useDispatch();
  const { customFields } = useSelector((state) => state.customFields);
  const [aiDraft, setAiDraft] = useState<WorkOrderAiDraft | null>(null);

  useUnsavedChanges(navigation, isFormDirty);

  const { isListening, isDrafting, startListening, stopListening } =
    useVoiceWorkOrderDraft((draft) => {
      if (draft.success) {
        setAiDraft(draft);
      } else {
        showSnackBar(t('ai_draft_failed'), 'error');
      }
    });

  const defaultShape: { [key: string]: any } = {
    title: Yup.string().required(t('required_wo_title')),
    ...getCustomFieldsRequiredShape(
      customFields,
      CustomFieldEntityType.WORK_ORDER,
      t
    )
  };

  const onCreationSuccess = () => {
    showSnackBar(t('wo_create_success'), 'success');
    navigation.goBack();
  };
  const onCreationFailure = (err) =>
    showSnackBar(getErrorMessage(err, t('wo_create_failure')), 'error');
  const getFieldsAndShapes = (): [Array<IField>, { [key: string]: any }] => {
    const fields = [
      ...getWorkOrderFields(t),
      ...getCustomFieldsIFields(customFields, CustomFieldEntityType.WORK_ORDER)
    ];
    return getWOFieldsAndShapes(fields, defaultShape);
  };
  return (
    <View style={styles.container}>
      <View style={styles.voiceDraftContainer}>
        <Button
          icon={isListening ? 'stop-circle' : 'microphone'}
          mode="outlined"
          loading={isDrafting}
          disabled={isDrafting}
          onPress={isListening ? stopListening : startListening}
        >
          {isListening
            ? t('stop_and_fill')
            : isDrafting
            ? t('drafting')
            : t('fill_from_voice')}
        </Button>
        {aiDraft?.success && (
          <Text style={styles.voiceDraftAppliedText}>
            {t('ai_draft_applied')}
          </Text>
        )}
      </View>
      <Form
        fields={[
          ...getFieldsAndShapes()[0],
          {
            name: 'assetStatus',
            type: 'select',
            label: t('asset_status'),
            placeholder: t('select_asset_status'),
            items: assetStatuses.map((assetStatus) => ({
              label: t(assetStatus.status),
              value: assetStatus.status,
              color: assetStatus.color(theme)
            }))
          }
        ]}
        validation={Yup.object().shape(getFieldsAndShapes()[1])}
        navigation={navigation}
        submitText={t('save')}
        values={{
          requiredSignature: false,
          dueDate: null,
          location: route.params?.location
            ? {
                label: route.params.location.name,
                value: route.params.location.id.toString()
              }
            : null,
          asset: route.params?.asset
            ? {
                label: route.params.asset.name,
                value: route.params.asset.id.toString()
              }
            : null,
          estimatedDuration: 1,
          ...(aiDraft?.success
            ? {
                title: aiDraft.title,
                description: aiDraft.description,
                priority: aiDraft.priority
              }
            : {})
        }}
        onChange={() => setIsFormDirty(true)}
        onSubmit={async (values) => {
          setIsFormDirty(false);
          let formattedValues = formatWorkOrderValues(values);
          formattedValues = formatCustomFields(formattedValues);
          try {
            const uploadedFiles = await uploadFiles(
              formattedValues.files,
              formattedValues.image
            );
            const imageAndFiles = getImageAndFiles(uploadedFiles);
            formattedValues = {
              ...formattedValues,
              image: imageAndFiles.image,
              files: imageAndFiles.files
            };
            await dispatch(addWorkOrder(formattedValues));
            onCreationSuccess();
          } catch (err) {
            onCreationFailure(err);
            throw err;
          }
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1
  },
  voiceDraftContainer: {
    paddingHorizontal: 15,
    paddingTop: 15,
    alignItems: 'flex-start'
  },
  voiceDraftAppliedText: {
    marginTop: 6,
    fontSize: 12
  }
});
