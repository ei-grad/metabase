// eslint-disable-next-line no-restricted-imports
import styled from "@emotion/styled";

import Input from "metabase/common/components/Input/Input";
import InputBlurChange from "metabase/common/components/InputBlurChange";
import { color } from "metabase/lib/colors";

export const TableName = styled.div`
  font-weight: 700;
  font-size: 1.25rem;
  padding: 0.75rem 0.5rem;
  border: 1px solid transparent;
`;

export const TableDescription = styled.div`
  font-weight: 400;
  font-size: 0.875rem;
  padding: 0.75rem 0.5rem;
  border: 1px solid transparent;
  margin-top: -1px;
`;

export const TableNameInput = styled(InputBlurChange)`
  ${Input.Field} {
    font-size: 20px;
    color: var(--mb-color-text-dark);
    border-radius: 8px 8px 0 0;
    background-color: var(--mb-color-bg-light);
    padding: 0.75rem 1.5rem;
  }
`;

export const TableDescriptionInput = styled(InputBlurChange)`
  ${Input.Field} {
    color: var(--mb-color-text-dark);
    margin-top: -1px;
    border-radius: 0 0 8px 8px;
    font-weight: 400;
    font-size: 14px;
    background-color: var(--mb-color-bg-light);
    padding: 0.75rem 1.5rem;
  }
`;

interface VisibilityBadgeProps {
  isChecked: boolean;
}

export const VisibilityBadge = styled.span<VisibilityBadgeProps>`
  margin: 0 0.5rem;
  font-weight: bold;
  cursor: pointer;
  color: ${(props) => (props.isChecked ? color("brand") : color("text-dark"))};

  &:hover {
    color: var(--mb-color-brand);
  }
`;
