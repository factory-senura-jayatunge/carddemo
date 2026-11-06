import { Group, Text, Title } from '@mantine/core';
import { useEffect, type ReactNode } from 'react';
import classes from './PageTitle.module.css';

export function useDocumentTitle(title: string): void {
  useEffect(() => {
    document.title = `${title} · CardDemo`;
  }, [title]);
}

interface PageTitleProps {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}

/** The heading block at the top of a page's content. */
export function PageTitle({ title, description, actions }: PageTitleProps) {
  useDocumentTitle(title);
  return (
    <div className={classes.root}>
      <div className={classes.titles}>
        <Title order={1} className={classes.title}>
          {title}
        </Title>
        {description && (
          <Text c="dimmed" size="md" className={classes.description}>
            {description}
          </Text>
        )}
      </div>
      {actions && (
        <Group gap="sm" className={classes.actions}>
          {actions}
        </Group>
      )}
    </div>
  );
}
