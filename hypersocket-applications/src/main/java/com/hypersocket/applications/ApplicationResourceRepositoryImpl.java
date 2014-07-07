package com.hypersocket.applications;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.hypersocket.resource.AbstractAssignableResourceRepositoryImpl;

@Repository
@Transactional
public class ApplicationResourceRepositoryImpl extends
		AbstractAssignableResourceRepositoryImpl<ApplicationResource> implements
		ApplicationResourceRepository {

	@Override
	protected Class<ApplicationResource> getResourceClass() {
		return ApplicationResource.class;
	}

}
