import { useState } from "react";
import { t } from "ttag";

import { archiveAndTrack } from "metabase/archive/analytics";
import ModalContent from "metabase/common/components/ModalContent";
import { FormMessage } from "metabase/forms";
import { Button, FocusTrap, Group } from "metabase/ui";

interface ArchiveModalProps {
  title?: string;
  message: React.ReactNode;
  model: "card" | "model" | "metric" | "dashboard" | "collection";
  modelId: number;
  isLoading?: boolean;
  onArchive: () => Promise<void>;
  onClose?: () => void;
}

export const ArchiveModal = ({
  title,
  message,
  model,
  modelId,
  isLoading,
  onClose,
  onArchive,
}: ArchiveModalProps) => {
  const [error, setError] = useState();

  const archive = async () => {
    await archiveAndTrack({
      archive: onArchive,
      model,
      modelId,
      triggeredFrom: "detail_page",
    })
      .catch((error) => setError(error))
      .finally(() => {
        onClose?.();
      });
  };

  return (
    <ModalContent
      title={title || t`Trash this?`}
      footer={[
        error ? <FormMessage key="message" formError={error} /> : null,
        <FocusTrap key="buttons">
          <Group gap="0.5rem">
            <Button key="cancel" onClick={onClose}>
              {t`Cancel`}
            </Button>
            <Button
              key="archive"
              color="error"
              variant="filled"
              onClick={archive}
              loading={isLoading}
              data-autofocus
            >
              {t`Move to trash`}
            </Button>
          </Group>
        </FocusTrap>,
      ]}
    >
      {message}
    </ModalContent>
  );
};
