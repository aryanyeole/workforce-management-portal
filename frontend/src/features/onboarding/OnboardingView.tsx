import { useState } from 'react';
import { OnboardingEmployeeDetail } from './OnboardingEmployeeDetail';
import { OnboardingEmployeeList } from './OnboardingEmployeeList';

/** No router (Task 1) — list/detail is just local state, same pattern as Shell's own View switch. */
export function OnboardingView() {
  const [selectedEmployeeId, setSelectedEmployeeId] = useState<number | null>(null);

  if (selectedEmployeeId === null) {
    return <OnboardingEmployeeList onSelect={setSelectedEmployeeId} />;
  }
  return <OnboardingEmployeeDetail employeeId={selectedEmployeeId} onBack={() => setSelectedEmployeeId(null)} />;
}
