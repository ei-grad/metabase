// eslint-disable-next-line no-restricted-imports
import styled from "@emotion/styled";

import InputBlurChange from "metabase/common/components/InputBlurChange";
import SelectButton from "metabase/common/components/SelectButton";
import { alpha, color } from "metabase/lib/colors";

export const FieldMappingRoot = styled.div`
  padding: 1rem 4rem;
  border: 1px solid ${() => alpha("accent2", 0.2)};
  border-radius: 0.5rem;
`;

export const FieldMappingContainer = styled.div`
  display: flex;
  align-items: center;
`;

interface FieldSelectButtonProps {
  hasError: boolean;
}

export const FieldSelectButton = styled(SelectButton)<FieldSelectButtonProps>`
  border-color: ${(props) =>
    props.hasError ? color("error") : alpha("accent2", 0.2)};
`;

export const FieldValueMappingInput = styled(InputBlurChange)`
  width: auto;
`;
