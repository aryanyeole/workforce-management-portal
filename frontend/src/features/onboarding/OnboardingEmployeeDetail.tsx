import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ApiError } from '../../api/client';
import { createTask, fetchDocuments, fetchEmployee, fetchTasks, updateTaskStatus, uploadDocument } from '../../api/onboarding';
import type { OnboardingTaskStatus } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';

const TASK_STATUSES: OnboardingTaskStatus[] = ['PENDING', 'IN_PROGRESS', 'COMPLETED'];

function errorDetail(err: unknown, fallback: string): string {
  return err instanceof ApiError ? (err.problem?.detail ?? err.message) : fallback;
}

/**
 * Employee detail + onboarding checklist (tasks) + documents.
 *
 * Row-level access: fetchEmployee 404s identically whether employeeId
 * doesn't exist or simply isn't in the caller's VisibilityScope (see
 * EmployeeService.requireVisible's own comment — ADR 0001's leak-safe
 * intent, confirmed live for this task's step 7). That error is rendered
 * here the same way every other query error in this app is — no special
 * "you don't have access" branch, because the API deliberately gives no
 * way to distinguish that from "doesn't exist."
 *
 * Task creation (POST) is route-restricted to HR_ADMIN/MANAGER
 * (PermissionRegistry) — the form is hidden for any other role, but that's
 * presentation only, same convention as Shell's nav (see its comment): an
 * EMPLOYEE calling the endpoint directly still gets the real 403, this
 * hiding just avoids showing a control that would always fail.
 */
export function OnboardingEmployeeDetail({ employeeId, onBack }: { employeeId: number; onBack: () => void }) {
  const { user } = useAuth();
  const queryClient = useQueryClient();

  const employeeQuery = useQuery({ queryKey: ['employee', employeeId], queryFn: () => fetchEmployee(employeeId) });
  const tasksQuery = useQuery({
    queryKey: ['tasks', employeeId],
    queryFn: () => fetchTasks(employeeId),
    enabled: employeeQuery.isSuccess,
  });
  const documentsQuery = useQuery({
    queryKey: ['documents', employeeId],
    queryFn: () => fetchDocuments(employeeId),
    enabled: employeeQuery.isSuccess,
  });

  const [taskTitle, setTaskTitle] = useState('');
  const [taskDueDate, setTaskDueDate] = useState('');
  const [taskFormError, setTaskFormError] = useState<string | null>(null);

  const createTaskMutation = useMutation({
    mutationFn: () => createTask(employeeId, { title: taskTitle.trim(), dueDate: taskDueDate || undefined }),
    onSuccess: () => {
      setTaskTitle('');
      setTaskDueDate('');
      setTaskFormError(null);
      queryClient.invalidateQueries({ queryKey: ['tasks', employeeId] });
    },
    onError: (err) => setTaskFormError(errorDetail(err, 'Could not create task.')),
  });

  const taskStatusMutation = useMutation({
    mutationFn: ({ taskId, status }: { taskId: number; status: OnboardingTaskStatus }) => updateTaskStatus(taskId, status),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['tasks', employeeId] }),
  });

  const [documentType, setDocumentType] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [docFormError, setDocFormError] = useState<string | null>(null);

  const uploadMutation = useMutation({
    mutationFn: () => {
      if (!file) {
        throw new Error('no file selected'); // guarded below; never actually thrown
      }
      return uploadDocument(employeeId, documentType.trim(), file);
    },
    onSuccess: () => {
      setDocumentType('');
      setFile(null);
      setDocFormError(null);
      queryClient.invalidateQueries({ queryKey: ['documents', employeeId] });
    },
    onError: (err) => setDocFormError(errorDetail(err, 'Could not upload document.')),
  });

  function handleCreateTask(e: React.FormEvent): void {
    e.preventDefault();
    setTaskFormError(null);
    if (!taskTitle.trim()) {
      setTaskFormError('Title is required.');
      return;
    }
    createTaskMutation.mutate();
  }

  function handleUpload(e: React.FormEvent): void {
    e.preventDefault();
    setDocFormError(null);
    if (!documentType.trim()) {
      setDocFormError('Document type is required.');
      return;
    }
    if (!file) {
      setDocFormError('Choose a file.');
      return;
    }
    uploadMutation.mutate();
  }

  const canManageTasks = user?.role === 'HR_ADMIN' || user?.role === 'MANAGER';

  if (employeeQuery.isPending) {
    return <p className="approvals-status">Loading employee…</p>;
  }

  if (employeeQuery.isError) {
    const message = errorDetail(employeeQuery.error, 'Could not load this employee.');
    return (
      <div>
        <button type="button" onClick={onBack}>
          ← Back to employees
        </button>
        <p className="approvals-status approvals-error" role="alert">
          {message}
        </p>
      </div>
    );
  }

  const employee = employeeQuery.data;

  return (
    <div className="onboarding-detail">
      <button type="button" onClick={onBack}>
        ← Back to employees
      </button>

      <h2>
        {employee.firstName} {employee.lastName}
      </h2>
      <dl className="onboarding-employee-summary">
        <dt>Email</dt>
        <dd>{employee.email}</dd>
        <dt>Department</dt>
        <dd>{employee.departmentName ?? '—'}</dd>
        <dt>Status</dt>
        <dd>{employee.employmentStatus}</dd>
        <dt>Hired</dt>
        <dd>{employee.hireDate}</dd>
      </dl>

      <section>
        <h3>Onboarding tasks</h3>
        {tasksQuery.isPending && <p className="approvals-status">Loading tasks…</p>}
        {tasksQuery.isError && (
          <p className="approvals-status approvals-error" role="alert">
            {errorDetail(tasksQuery.error, 'Could not load tasks.')}
          </p>
        )}
        {tasksQuery.data && (
          <ul className="onboarding-task-list">
            {tasksQuery.data.length === 0 && <li className="approvals-status">No tasks yet.</li>}
            {tasksQuery.data.map((task) => (
              <li key={task.id} className="onboarding-task-row">
                <span className="onboarding-task-title">{task.title}</span>
                {task.dueDate && <span className="onboarding-task-due">due {task.dueDate}</span>}
                <select
                  value={task.status}
                  disabled={taskStatusMutation.isPending}
                  onChange={(e) =>
                    taskStatusMutation.mutate({ taskId: task.id, status: e.target.value as OnboardingTaskStatus })
                  }
                >
                  {TASK_STATUSES.map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
              </li>
            ))}
          </ul>
        )}
        {taskStatusMutation.isError && (
          <p className="expenses-form-error" role="alert">
            {errorDetail(taskStatusMutation.error, 'Could not update task status.')}
          </p>
        )}

        {canManageTasks && (
          <form className="onboarding-task-form" onSubmit={handleCreateTask}>
            <input
              type="text"
              placeholder="New task title"
              value={taskTitle}
              onChange={(e) => setTaskTitle(e.target.value)}
            />
            <input type="date" value={taskDueDate} onChange={(e) => setTaskDueDate(e.target.value)} />
            <button type="submit" disabled={createTaskMutation.isPending}>
              Add task
            </button>
            {taskFormError && (
              <p className="expenses-form-error" role="alert">
                {taskFormError}
              </p>
            )}
          </form>
        )}
      </section>

      <section>
        <h3>Documents</h3>
        {documentsQuery.isPending && <p className="approvals-status">Loading documents…</p>}
        {documentsQuery.isError && (
          <p className="approvals-status approvals-error" role="alert">
            {errorDetail(documentsQuery.error, 'Could not load documents.')}
          </p>
        )}
        {documentsQuery.data && (
          <ul className="onboarding-document-list">
            {documentsQuery.data.length === 0 && <li className="approvals-status">No documents yet.</li>}
            {documentsQuery.data.map((doc) => (
              <li key={doc.id} className="onboarding-document-row">
                <span>{doc.fileName}</span>
                <span className="onboarding-document-type">{doc.documentType}</span>
                <span className={`expenses-status-badge expenses-status-${doc.status.toLowerCase()}`}>{doc.status}</span>
              </li>
            ))}
          </ul>
        )}

        <form className="onboarding-document-form" onSubmit={handleUpload}>
          <input
            type="text"
            placeholder="Document type (e.g. I-9)"
            value={documentType}
            onChange={(e) => setDocumentType(e.target.value)}
          />
          <input type="file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          <button type="submit" disabled={uploadMutation.isPending}>
            {uploadMutation.isPending ? 'Uploading…' : 'Upload'}
          </button>
          {docFormError && (
            <p className="expenses-form-error" role="alert">
              {docFormError}
            </p>
          )}
        </form>
      </section>
    </div>
  );
}
